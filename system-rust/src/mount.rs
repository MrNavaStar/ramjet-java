use std::fs;
use std::io;
use std::path::Path;
use std::path::PathBuf;
use std::os::unix::fs::PermissionsExt;
use anyhow::Context;
use nix::errno::Errno;
use nix::mount::{mount, umount, MsFlags};
use tempfile::Builder;

fn create_mount_point(path: &Path, mode: u32) -> anyhow::Result<()> {
    fs::create_dir_all(path).with_context(|| format!("create {}", path.display()))?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))
        .with_context(|| format!("set permissions on {}", path.display()))?;
    Ok(())
}

fn mount_virtual_fs(source: &str, target: &Path, filesystem: &str, options: Option<&str>) -> anyhow::Result<()> {
    create_mount_point(target, 0o755)?;

    if let Err(error) = mount(Some(source), target, Some(filesystem), MsFlags::empty(), options) {
        if error != Errno::EBUSY {
            return Err(error.into());
        }
    }
    Ok(())
}

pub fn mount_kernel_filesystems(root: &Path) -> anyhow::Result<()> {
    mount_virtual_fs("proc", &root.join("proc"), "proc", None).context("mount proc")?;
    mount_virtual_fs("sysfs", &root.join("sys"), "sysfs", None).context("mount sysfs")?;
    mount_virtual_fs("devtmpfs", &root.join("dev"), "devtmpfs", Some("mode=0755")).context("mount devtmpfs")?;
    mount_virtual_fs("tmpfs", &root.join("run"), "tmpfs", Some("mode=0755")).context("mount /run")?;
    mount_virtual_fs("tmpfs", &root.join("tmp"), "tmpfs", Some("mode=1777")).context("mount /tmp")?;

    let dev_pts = root.join("dev/pts");
    create_mount_point(&dev_pts, 0o755).context("create /dev/pts")?;
    mount_virtual_fs("devpts", &dev_pts, "devpts", Some("mode=0620")).context("mount /dev/pts")?;

    let dev_shm = root.join("dev/shm");
    create_mount_point(&dev_shm, 0o1777).context("create /dev/shm")?;
    mount_virtual_fs("tmpfs", &dev_shm, "tmpfs", Some("mode=1777")).context("mount /dev/shm")?;

    Ok(())
}

pub fn mount_device(device: PathBuf, device_path: impl AsRef<Path>, mount_path: impl AsRef<Path>) -> io::Result<PathBuf> {
    let device_path = device_path.as_ref();
    let mount_path = mount_path.as_ref();

    if !device.exists() {
        return Err(io::Error::new(io::ErrorKind::NotFound, format!("disk not found: {}", device.display())));
    }

    // Create a unique mount directory.
    let mount_dir = Builder::new().tempdir()?.keep();
    fs::create_dir_all(&mount_dir)?;

    // Mount the filesystem.
    mount(Some(device.as_path()),&mount_dir, None::<&str>,MsFlags::empty(),None::<&str>)
    .map_err(|e| {
        let _ = fs::remove_dir(&mount_dir);
        io::Error::other(format!("failed to mount {} at {}: {e}", device.display(), mount_dir.display()))
    })?;

    // Path within the mounted filesystem.
    let source_path = mount_dir.join(device_path);

    // Create the bind-mount destination.
    if source_path.is_dir() {
        fs::create_dir_all(&source_path)?;
        fs::create_dir_all(mount_path)?;
    } else {
        if let Some(parent) = mount_path.parent() {
            fs::create_dir_all(parent)?;
        }

        if !mount_path.exists() {
            fs::File::create(mount_path)?;
        }
    }

    // Bind mount source_path -> mount_path.
    mount(Some(source_path.as_path()),mount_path,None::<&str>,MsFlags::MS_BIND,None::<&str>)
    .map_err(|e| {
        // Best-effort cleanup.
        let _ = umount(&mount_dir);
        let _ = fs::remove_dir(&mount_dir);
        io::Error::other(format!("failed to bind mount {} to {}: {e}", source_path.display(), mount_path.display()))
    })?;

    Ok(mount_dir)
}
