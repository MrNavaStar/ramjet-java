use core::net::{Ipv4Addr, SocketAddrV4};
use std::net::Ipv6Addr;
use edge_net::dhcp::client::Client;
use edge_net::dhcp::io::{client::Lease, DEFAULT_CLIENT_PORT, DEFAULT_SERVER_PORT};
use edge_net::mdns::buf::BufferAccess;
use edge_net::mdns::domain::base::Ttl;
use edge_net::mdns::host::Host;
use edge_net::mdns::{io, HostAnswersMdnsHandler};
use edge_net::mdns::io::{MdnsIoError, DEFAULT_SOCKET};
use edge_net::nal::{MacAddr, RawBind, UdpBind, UdpSplit};
use edge_net::raw::io::RawSocket2Udp;

async fn run_dhcp(if_index: u32, if_mac: MacAddr) -> Result<(), anyhow::Error> {
    let mut client = Client::new(rand::rng(), if_mac);

    let stack = edge_net::std::Interface::new(if_index);
    let mut buf = [0; 1500];

    loop {
        let mut socket: RawSocket2Udp<_> = RawSocket2Udp::new(
            stack.bind().await?,
            Some(SocketAddrV4::new(
                Ipv4Addr::UNSPECIFIED,
                DEFAULT_CLIENT_PORT,
            )),
            Some(SocketAddrV4::new(
                Ipv4Addr::UNSPECIFIED,
                DEFAULT_SERVER_PORT,
            )),
            [255; 6], // Broadcast
        );

        let (mut lease, options) = Lease::new(&mut client, &mut socket, &mut buf).await?;

        lease.keep(&mut client, &mut socket, &mut buf).await?;
    }
}

async fn run_mdns<T, RB, SB>(
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
}