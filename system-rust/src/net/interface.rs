use std::fs;
use std::net::IpAddr;

use anyhow::{anyhow, Result};
use futures_util::TryStreamExt;
use nix::net::if_::if_nametoindex;
use rtnetlink::packet_route::link::{LinkAttribute, LinkFlags, LinkLayerType, LinkMessage};
use rtnetlink::{new_connection, Handle, LinkUnspec, RouteMessageBuilder};

pub async fn interface_to_link(interface: &str, handle: &Handle) -> Result<LinkMessage> {
    handle
        .link()
        .get()
        .match_name(interface.to_string())
        .execute()
        .try_next()
        .await?
        .ok_or_else(|| anyhow!("interface not found"))
}

pub async fn interface_up(interface: &str) -> Result<()> {
    let (connection, handle, _) = new_connection()?;
    tokio::spawn(connection);

    let link = interface_to_link(interface, &handle).await?;
    handle
        .link()
        .set(LinkUnspec::new_with_index(link.header.index).up().build())
        .execute()
        .await?;

    Ok(())
}

pub async fn set_address(interface: &str, address: IpAddr, prefix_len: u8) -> Result<()> {
    let (connection, handle, _) = new_connection()?;
    tokio::spawn(connection);

    let link = interface_to_link(interface, &handle).await?;
    handle
        .address()
        .add(link.header.index, address.into(), prefix_len)
        .execute()
        .await?;

    Ok(())
}

pub async fn set_default_route(interface: &str, gateway: IpAddr) -> Result<()> {
    let (connection, handle, _) = new_connection()?;
    tokio::spawn(connection);

    handle
        .route()
        .add(
            RouteMessageBuilder::<IpAddr>::new()
                .gateway(gateway)?
                .output_interface(if_nametoindex(interface)?)
                .build(),
        )
        .execute()
        .await?;

    Ok(())
}

pub fn get_mac(interface: &str) -> Result<[u8; 6]> {
    let mac: [u8; 6] = fs::read_to_string(format!("/sys/class/net/{interface}/address"))?
        .trim()
        .split(':')
        .map(|part| u8::from_str_radix(part, 16))
        .collect::<Result<Vec<_>, _>>()?
        .try_into()
        .map_err(|_| anyhow!("invalid MAC address"))?;

    Ok(mac)
}

/// Returns the names of all ethernet interfaces on the system, excluding the loopback interface.
pub async fn interfaces() -> Result<Vec<String>> {
    let (connection, handle, _) = new_connection()?;
    tokio::spawn(connection);

    let mut links = handle.link().get().execute();
    let mut interfaces = Vec::new();

    while let Some(message) = links.try_next().await? {
        if message.header.flags.contains(LinkFlags::Loopback) {
            continue;
        }

        if message.header.link_layer_type != LinkLayerType::Ether {
            continue;
        }

        let name = message
            .attributes
            .iter()
            .find_map(|attribute| match attribute {
                LinkAttribute::IfName(name) => Some(name.clone()),
                _ => None,
            })
            .ok_or_else(|| anyhow!("interface with index {} has no name", message.header.index))?;

        interfaces.push(name);
    }

    Ok(interfaces)
}
