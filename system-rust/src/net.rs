use core::net::{Ipv4Addr, SocketAddrV4};
use std::fs;
use std::net::{IpAddr, Ipv6Addr};
use edge_net::dhcp::client::Client;
use edge_net::dhcp::io::{client::Lease, DEFAULT_CLIENT_PORT, DEFAULT_SERVER_PORT};
use edge_net::mdns::buf::BufferAccess;
use edge_net::mdns::domain::base::Ttl;
use edge_net::mdns::host::Host;
use edge_net::mdns::{io, HostAnswersMdnsHandler};
use edge_net::mdns::io::{MdnsIoError, DEFAULT_SOCKET};
use edge_net::nal::{MacAddr, RawBind, UdpBind, UdpSplit};
use edge_net::raw::io::RawSocket2Udp;
use futures_util::TryStreamExt;
use nix::sys::signal::Signal;
use nix::net::if_::if_nametoindex;
use rtnetlink::{new_connection, Handle, LinkUnspec, RouteMessageBuilder};
use rtnetlink::packet_route::link::LinkMessage;
use rtnetlink::packet_route::link::LinkFlags;

async fn interface_to_link(interface: &str, handle: &Handle) -> anyhow::Result<LinkMessage> {
    handle
        .link()
        .get()
        .match_name(interface.to_string())
        .execute()
        .try_next()
        .await?
        .ok_or_else(|| anyhow::anyhow!("interface not found"))
}

async fn interface_up(interface: &str) -> anyhow::Result<()> {
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

async fn set_address(interface: &str, address: IpAddr, prefix_len: u8) -> anyhow::Result<()> {
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

async fn set_default_route(interface: &str, gateway: IpAddr) -> anyhow::Result<()> {
    let (connection, handle, _) = new_connection()?;
    tokio::spawn(connection);

    handle.route().add(
        RouteMessageBuilder::<IpAddr>::new()
        .gateway(gateway)?
        .output_interface(if_nametoindex(interface)?)
        .build()
    ).execute().await?;
    Ok(())
}

fn get_mac(interface: &str) -> anyhow::Result<MacAddr> {
    fs::read_to_string(format!("/sys/class/net/{interface}/address"))?
        .trim()
        .split(':')
        .map(|part| u8::from_str_radix(part, 16))
        .collect::<Result<Vec<_>, _>>()?
        .try_into()
        .map_err(|_| anyhow::anyhow!("invalid MAC address"))
}

pub async fn run_dhcp(interface: &str) -> Result<(), anyhow::Error> {
    let mut client = Client::new(rand::rng(), get_mac(interface)?);
    let stack = edge_net::std::Interface::new(if_nametoindex(interface)?);
    let mut buf = [0; 1500];

    loop {
        let mut socket: RawSocket2Udp<_> = RawSocket2Udp::new(
            stack.bind().await?,
            Some(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DEFAULT_CLIENT_PORT)),
            Some(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DEFAULT_SERVER_PORT)),
            [255; 6], // Broadcast
        );

        let (mut lease, network_info) = Lease::new(&mut client, &mut socket, &mut buf).await?;

        network_info.gateway.map(async |gateway| set_default_route(interface, IpAddr::from(gateway)).await);
        set_address(interface, IpAddr::from(lease.ip), 0).await?;
        interface_up(interface).await?;

        lease.keep(&mut client, &mut socket, &mut buf).await?;
    }
}

/*async fn run_mdns<T, RB, SB>(
    stack: &T,
    recv_buf: RB,
    send_buf: SB,
    our_name: &str,
    our_ip: Ipv4Addr,
) -> Result<(), MdnsIoError<T::Error>>
where
    T: UdpBind,
    RB: BufferAccess<[u8]>,
    SB: BufferAccess<[u8]>,
{

    let mut socket = io::bind(stack, DEFAULT_SOCKET, Some(Ipv4Addr::UNSPECIFIED), Some(0)).await?;

    let (recv, send) = socket.split();

    let host = Host {
        hostname: our_name,
        ipv4: our_ip,
        ipv6: Ipv6Addr::UNSPECIFIED,
        ttl: Ttl::from_secs(60),
    };

    // A way to notify the mDNS responder that the data in `Host` had changed
    // We don't use it in this example, because the data is hard-coded
    let signal = Signal::<NoopRawMutex, _>::new();

    let mdns = io::Mdns::new(
        Some(Ipv4Addr::UNSPECIFIED),
        Some(0),
        recv,
        send,
        recv_buf,
        send_buf,
        rand::rng(),
        &signal,
    );

    mdns.run(HostAnswersMdnsHandler::new(&host)).await
}*/