//! Network configuration for the init system.
//!
//! This module brings up discovered interfaces, runs automatic DHCP on them and
//! configures DNS through `/etc/resolv.conf`.

pub mod dhcp;
pub mod interface;

use std::collections::HashMap;

use anyhow::{anyhow, Result};
use futures_util::StreamExt;
use rtnetlink::packet_core::NetlinkPayload;
use rtnetlink::packet_route::RouteNetlinkMessage;
use rtnetlink::packet_route::link::{LinkAttribute, LinkFlags, LinkMessage};
use rtnetlink::{new_multicast_connection, MulticastGroup};
use tokio::sync::Mutex;
use tokio::task::JoinHandle;

type ManagedTasks = HashMap<String, JoinHandle<anyhow::Result<(), anyhow::Error>>>;

/// Brings up the discovered ethernet interfaces and runs automatic DHCP on them.
pub async fn run() -> Result<(), anyhow::Error> {
    supervise().await
}

enum LinkEvent {
    New,
    Update,
    Delete,
}

async fn supervise() -> Result<(), anyhow::Error> {
    let mut managed: Mutex<ManagedTasks> = Mutex::new(HashMap::new());

    let interfaces = interface::interfaces().await?;
    for interface in interfaces {
        start(&mut managed, &interface).await?;
    }

    let (connection, _handle, mut messages) = new_multicast_connection(&[MulticastGroup::Link])?;
    tokio::spawn(connection);

    while let Some((message, _socket_addr)) = messages.next().await {
        let (link, event) = match message.payload {
            NetlinkPayload::InnerMessage(RouteNetlinkMessage::NewLink(link)) => (link, LinkEvent::New),
            NetlinkPayload::InnerMessage(RouteNetlinkMessage::SetLink(link)) => (link, LinkEvent::Update),
            NetlinkPayload::InnerMessage(RouteNetlinkMessage::DelLink(_)) => (LinkMessage::default(), LinkEvent::Delete),
            _ => continue,
        };

        let name = link
            .attributes
            .iter()
            .find_map(|attribute| match attribute {
                LinkAttribute::IfName(name) => Some(name.clone()),
                _ => None,
            })
            .ok_or_else(|| anyhow!("link message with index {} has no interface name", link.header.index))?;

        if link.header.flags.contains(LinkFlags::Loopback) {
            continue;
        }

        let up = link.header.flags.contains(LinkFlags::Up);
        let interface_name = name;
        let mut managed_guard = managed.lock().await;
        let known = managed_guard.contains_key(&interface_name);
        let stop = known && (!up || matches!(event, LinkEvent::Delete));
        let handle = if stop { managed_guard.remove(&interface_name) } else { None };

        drop(managed_guard);

        if stop {
            if let Some(handle) = handle {
                handle.abort();
            }

            if !matches!(event, LinkEvent::Delete) {
                interface::interface_down(&interface_name).await?;
            }
        } else if !known && matches!(event, LinkEvent::New) {
            let name = interface_name.clone();
            let handle = tokio::spawn(async move { dhcp::run(&name).await });
            let mut guard = managed.lock().await;
            guard.insert(interface_name, handle);
        }
    }

    Ok(())
}

async fn start(managed: &mut Mutex<ManagedTasks>, interface: &str) -> Result<()> {
    let mut guard = managed.lock().await;
    if guard.contains_key(interface) {
        return Ok(());
    }

    let name = interface.to_string();
    let handle = {
        let name = name.clone();
        tokio::spawn(async move { dhcp::run(&name).await })
    };
    guard.insert(name, handle);

    Ok(())
}
