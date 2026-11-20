//! Network configuration for the init system.
//!
//! This module brings up discovered interfaces, runs automatic DHCP on them and
//! configures DNS through `/etc/resolv.conf`.

pub mod dhcp;
pub mod interface;

use anyhow::Result;

/// Brings up the discovered ethernet interfaces and runs automatic DHCP on them.
pub async fn run() -> Result<(), anyhow::Error> {
    let interfaces = interface::interfaces().await?;

    for interface in interfaces {
        tokio::spawn(async move { dhcp::run(&interface).await });
    }

    Ok(())
}
