use std::{
    fs,
    io,
    path::{Path, PathBuf}
};
use nix::mount::{mount, umount, MsFlags};
use tempfile::Builder;

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
    fs::create_dir_all(&source_path)?;

    // Create the bind-mount destination.
    if source_path.is_dir() {
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
