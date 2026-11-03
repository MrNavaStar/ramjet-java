//! Lua-backed configuration for Rust.
//!
//! A [`LuaConfigSource`] represents one or more Lua files. Calling
//! [`LuaConfigSource::resolve`] evaluates those files and deserializes the
//! resulting Lua table into any `serde::Deserialize` type.
//!
//! Each file must return a Lua table:
//!
//! ```lua
//! return {
//!     host = "127.0.0.1",
//!     port = 8080,
//! }
//! ```
//!
//! When multiple files are supplied, they are evaluated in order. Their
//! returned tables are deep-merged, with later files overriding earlier files.
//! The same Lua VM is used for every file, so later files may also use globals
//! or functions defined by earlier files.

use std::{
    fmt,
    fs,
    path::{Path, PathBuf},
};

use mlua::{Lua, LuaSerdeExt, Table, Value};
use serde::de::DeserializeOwned;
use thiserror::Error;

/// A source of Lua configuration files.
///
/// The source is deliberately separate from resolution, so it can be passed
/// around and resolved later.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LuaConfigSource {
    files: Vec<PathBuf>,
}

impl LuaConfigSource {
    /// Creates a source containing a single Lua file.
    pub fn file(path: impl Into<PathBuf>) -> Self {
        Self {
            files: vec![path.into()],
        }
    }

    /// Creates a source from several Lua files.
    ///
    /// Files are evaluated in the supplied order.
    pub fn files<I, P>(paths: I) -> Self
    where
        I: IntoIterator<Item = P>,
        P: Into<PathBuf>,
    {
        Self {
            files: paths.into_iter().map(Into::into).collect(),
        }
    }

    /// Creates an empty source.
    pub fn new() -> Self {
        Self::default()
    }

    /// Adds a Lua file to the source.
    pub fn push(&mut self, path: impl Into<PathBuf>) -> &mut Self {
        self.files.push(path.into());
        self
    }

    /// Returns the files in evaluation order.
    pub fn paths(&self) -> &[PathBuf] {
        &self.files
    }

    /// Returns whether the source contains no files.
    pub fn is_empty(&self) -> bool {
        self.files.is_empty()
    }

    /// Returns the number of files in the source.
    pub fn len(&self) -> usize {
        self.files.len()
    }

    /// Resolves this source into a Rust configuration structure.
    ///
    /// `T` only needs to implement [`serde::Deserialize`].
    pub fn resolve<T>(&self) -> Result<T, ConfigError>
    where
        T: DeserializeOwned,
    {
        self.resolve_with_options(mlua::serde::DeserializeOptions::default())
    }

    /// Resolves this source with custom mlua Serde options.
    pub fn resolve_with_options<T>(
        &self,
        options: mlua::serde::DeserializeOptions,
    ) -> Result<T, ConfigError>
    where
        T: DeserializeOwned,
    {
        if self.files.is_empty() {
            return Err(ConfigError::EmptySource);
        }

        let lua = Lua::new();
        let root = lua.create_table()?;

        for path in &self.files {
            let source = fs::read_to_string(path).map_err(|source| ConfigError::Read {
                path: path.clone(),
                source,
            })?;

            let value: Value = lua
                .load(&source)
                .set_name(path.to_string_lossy().as_ref())
                .eval()
                .map_err(|source| ConfigError::Lua {
                    path: path.clone(),
                    source,
                })?;

            let table = match value {
                Value::Table(table) => table,
                other => {
                    return Err(ConfigError::ExpectedTable {
                        path: path.clone(),
                        got: lua_type_name(&other),
                    });
                }
            };

            deep_merge(&lua, &root, &table).map_err(|source| ConfigError::Merge {
                path: path.clone(),
                source,
            })?;
        }

        lua.from_value_with(Value::Table(root), options)
            .map_err(ConfigError::Deserialize)
    }
}

impl<P> From<P> for LuaConfigSource
where
    P: Into<PathBuf>,
{
    fn from(path: P) -> Self {
        Self::file(path)
    }
}

impl fmt::Display for LuaConfigSource {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.files.len() == 1 {
            return self.files[0].display().fmt(f);
        }

        f.debug_list()
            .entries(self.files.iter().map(|p| p.display()))
            .finish()
    }
}

/// Errors produced while loading or resolving a Lua configuration.
#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("configuration source contains no files")]
    EmptySource,

    #[error("failed to read Lua config `{path}`: {source}")]
    Read {
        path: PathBuf,
        #[source]
        source: std::io::Error,
    },

    #[error("Lua error in `{path}`: {source}")]
    Lua {
        path: PathBuf,
        #[source]
        source: mlua::Error,
    },

    #[error("Lua config `{path}` must return a table, got {got}")]
    ExpectedTable { path: PathBuf, got: &'static str },

    #[error("failed to merge Lua config `{path}`: {source}")]
    Merge {
        path: PathBuf,
        #[source]
        source: mlua::Error,
    },

    #[error("failed to deserialize Lua config: {0}")]
    Deserialize(#[source] mlua::Error),
}

fn lua_type_name(value: &Value) -> &'static str {
    match value {
        Value::Nil => "nil",
        Value::Boolean(_) => "boolean",
        Value::LightUserData(_) => "lightuserdata",
        Value::Integer(_) => "integer",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Table(_) => "table",
        Value::Function(_) => "function",
        Value::Thread(_) => "thread",
        Value::UserData(_) => "userdata",
        Value::Error(_) => "error",
        Value::Other(_) => "other",
    }
}

fn deep_merge(lua: &Lua, destination: &Table, source: &Table) -> mlua::Result<()> {
    for pair in source.pairs::<Value, Value>() {
        let (key, source_value) = pair?;

        if let Value::Table(source_table) = &source_value {
            let existing: Value = destination.raw_get(key.clone())?;

            if let Value::Table(destination_table) = existing {
                deep_merge(lua, &destination_table, source_table)?;
                continue;
            }
        }

        destination.raw_set(key, source_value)?;
    }

    // Keep the parameter explicit: it documents that the merge operates on
    // values belonging to this Lua state and prevents accidental cross-state
    // implementations later.
    let _ = lua;

    Ok(())
}

/// Convenience function for resolving a single Lua file.
///
/// Equivalent to `LuaConfigSource::file(path).resolve()`.
pub fn from_file<T>(path: impl AsRef<Path>) -> Result<T, ConfigError>
where
    T: DeserializeOwned,
{
    LuaConfigSource::file(path.as_ref()).resolve()
}
