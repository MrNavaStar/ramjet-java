use std::ffi::CString;
use std::fs;
use std::path::Path;

use anyhow::{Context, Result};
use nix::unistd::{chdir, chroot, execve};

use crate::config;
use crate::mount::mount_kernel_filesystems;

fn exec_config(config: &config::OciConfig) -> Result<()> {
    let command = config.executable()?;
    let command: Vec<CString> = command
        .iter()
        .map(|argument| CString::new(argument.as_str()))
        .collect::<std::result::Result<_, _>>()
        .context("build OCI command arguments")?;
    let environment: Vec<CString> = config.env
        .iter()
        .map(|entry| CString::new(entry.as_str()))
        .collect::<std::result::Result<_, _>>()
        .context("build OCI environment")?;

    execve(&command[0], &command, &environment)?;
    unreachable!("execve returned unexpectedly")
}

pub async fn run(image_dir: impl AsRef<Path>, output_dir: impl AsRef<Path>) -> Result<()> {
    let output_dir = output_dir.as_ref();
    let image_dir = image_dir.as_ref();

    let config = config::load(image_dir)?;
    ocirender::convert_dir(image_dir, output_dir)
        .await
        .context("extract OCI image")?;

    fs::remove_dir_all(image_dir)?;

    mount_kernel_filesystems(output_dir)?;
    chroot(output_dir)?;
    chdir(config.working_dir())?;
    exec_config(&config)
}
