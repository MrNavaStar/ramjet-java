use std::ffi::CString;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use anyhow::{Context, Result};
use nix::errno::Errno;
use nix::mount::{mount, MsFlags};
use nix::unistd::{chdir, chroot, execve};

use crate::config;

fn create_mount_point(path: &Path, mode: u32) -> Result<()> {
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .with_context(|| format!("set permissions on {}", path.display()))?;
    Ok(())
}

fn mount_virtual_fs(source: &str, target: &Path, filesystem: &str, options: Option<&str>) -> Result<()> {
    create_mount_point(target, 0o755)?;

    if let Err(error) = mount(Some(source), target, Some(filesystem), MsFlags::empty(), options) {
        if error != Errno::EBUSY {
            return Err(error.into());
        }
    }
    Ok(())
}

fn mount_kernel_filesystems(root: &Path) -> Result<()> {
    mount_virtual_fs("proc", &root.join("proc"), "proc", None).context("mount proc")?;
    mount_virtual_fs("sysfs", &root.join("sys"), "sysfs", None).context("mount sysfs")?;
    mount_virtual_fs("devtmpfs", &root.join("dev"), "devtmpfs", Some("mode=0755"))
        .context("mount devtmpfs")?;
    mount_virtual_fs("tmpfs", &root.join("run"), "tmpfs", Some("mode=0755"))
        .context("mount /run")?;
    mount_virtual_fs("tmpfs", &root.join("tmp"), "tmpfs", Some("mode=1777"))
        .context("mount /tmp")?;

    let dev_pts = root.join("dev/pts");
    create_mount_point(&dev_pts, 0o755).context("create /dev/pts")?;
    mount_virtual_fs("devpts", &dev_pts, "devpts", Some("mode=0620")).context("mount /dev/pts")?;

    let dev_shm = root.join("dev/shm");
    create_mount_point(&dev_shm, 0o1777).context("create /dev/shm")?;
    mount_virtual_fs("tmpfs", &dev_shm, "tmpfs", Some("mode=1777")).context("mount /dev/shm")?;

    Ok(())
}

fn exec_configured_process(config: &config::OciConfig) -> Result<()> {
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
    mount_kernel_filesystems(output_dir)?;
    chroot(output_dir)?;
    chdir(config.working_dir())?;
    exec_configured_process(&config)
}
