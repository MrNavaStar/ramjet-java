use std::fs;
use std::path::Path;

use anyhow::{Context, Result};
use serde::Deserialize;

#[derive(Debug, Deserialize)]
pub struct OciConfig {
    #[serde(rename = "Cmd", default)]
    pub cmd: Vec<String>,
    #[serde(rename = "Entrypoint", default)]
    pub entrypoint: Vec<String>,
    #[serde(rename = "Env", default)]
    pub env: Vec<String>,
    #[serde(rename = "WorkingDir", default)]
    pub working_dir: Option<String>,
}

#[derive(Debug, Deserialize)]
struct OciConfigDocument {
    config: OciConfig,
}

impl OciConfig {
    pub fn executable(&self) -> Result<Vec<String>> {
        match (self.entrypoint.is_empty(), self.cmd.is_empty()) {
            (false, false) => {
                let mut command = self.entrypoint.clone();
                command.extend(self.cmd.iter().cloned());
                Ok(command)
            }
            (false, true) => Ok(self.entrypoint.clone()),
            (true, false) => Ok(self.cmd.clone()),
            (true, true) => anyhow::bail!("OCI config contains no command"),
        }
    }

    pub fn working_dir(&self) -> &str {
        self.working_dir
            .as_deref()
            .filter(|path| !path.is_empty())
            .unwrap_or("/")
    }
}

pub fn load(image_dir: impl AsRef<Path>) -> Result<OciConfig> {
    let path = image_dir.as_ref().join("config.json");
    let contents =
        fs::read_to_string(&path).with_context(|| format!("read OCI config {}", path.display()))?;
    let document: OciConfigDocument = serde_json::from_str(&contents)
        .with_context(|| format!("parse OCI config {}", path.display()))?;
    Ok(document.config)
}