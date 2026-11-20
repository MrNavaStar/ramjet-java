use std::collections::BTreeMap;
use std::fs;
use std::path::Path;
use std::os::unix::fs::PermissionsExt;
use anyhow::{Context, Result};
use nix::errno::Errno;
use nix::mount::{mount, MsFlags};

/// The mount point disks are mounted into before being bind mounted.
const DISK_MOUNT_POINT: &str = "/mnt";

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

/// Reads the disk mount entries from a JSON file, such as `/image/mounts.json`.
pub fn read_mounts(path: &Path) -> Result<BTreeMap<String, String>> {
    let contents = fs::read_to_string(path)
        .with_context(|| format!("read disk mounts {}", path.display()))?;
    let mounts = serde_json::from_str(&contents)
        .with_context(|| format!("parse disk mounts {}", path.display()))?;
    Ok(mounts)
}

/// Mounts every configured disk and bind mounts it into place.
pub fn mount_disks(mounts: &BTreeMap<String, String>, root: &Path) -> Result<()> {
    for (device, source) in mounts {
        let (source_on_device, target_on_host) = source
            .split_once(':')
            .with_context(|| format!("invalid disk mount entry for device {device}: {source}"))?;
        mount_disk(device, source_on_device, &*root.join(target_on_host.trim_start_matches("/")))?;
    }
    Ok(())
}

/// Mounts the disk identified by `device` into a unique mount point under the
/// disk mount root and bind mounts the given path on the device onto the host.
pub fn mount_disk(device: &str, source_on_device: &str, target_on_host: &Path) -> Result<()> {
    let mount_point = Path::new(DISK_MOUNT_POINT).join(device.trim_start_matches("/"));
    create_mount_point(&mount_point, 0o755)?;

    mount(Some(device), &mount_point, None::<&str>, MsFlags::empty(), None::<&str>)
        .with_context(|| format!("mount disk {device} at {}", mount_point.display()))?;

    // The path on the mounted device, e.g. `/mnt/dev/disk/by-label/media_01/some/path/on/device`.
    let source = mount_point.join(source_on_device.trim_start_matches('/'));
    
    if source.is_dir() {
        create_mount_point(&source, 0o755)?;
        create_mount_point(&target_on_host, 0o755)?;
    } else {
        if let Some(parent) = target_on_host.parent() {
            fs::create_dir_all(parent)?;
        }

        if !target_on_host.exists() {
            fs::File::create(&target_on_host)?;
        }
    }

    mount(Some(source.as_path()), target_on_host, None::<&str>, MsFlags::MS_BIND, None::<&str>)
        .with_context(|| format!("bind mount {} to {}", source.display(), target_on_host.display()))?;

    Ok(())
}
