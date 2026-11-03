use std::ffi::CString;
use std::fs;
use std::path::Path;

use anyhow::{Context, Result};
use nix::unistd::{chdir, chroot, execve, fork, ForkResult, Pid};

use crate::mount::mount_kernel_filesystems;
use crate::config;

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

pub fn fork_runtime() -> nix::Result<Pid> {
    match unsafe { fork()? } {
        ForkResult::Parent { child } => Ok(child),
        ForkResult::Child => {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("init: failed to create runtime executor");
            let result = runtime.block_on(run(Path::new("/image"), Path::new("/root")));
            if let Err(error) = result {
                eprintln!("init: runtime failed: {error:#}");
                std::process::exit(1);
            }
            std::process::exit(0);
        }
    }
}