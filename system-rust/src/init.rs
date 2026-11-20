use std::io;
use std::sync::atomic::{AtomicBool, Ordering};

use nix::errno::Errno;
use nix::sys::signal::{self, SaFlags, SigAction, SigHandler, SigSet, Signal};
use nix::sys::wait::{waitpid, WaitStatus};
use nix::unistd::{pause, Pid};
use tokio::task;
use crate::mount::mount_kernel_filesystems;
use crate::runtime::fork_runtime;

mod config;
mod mount;
mod runtime;
mod net;

static SHUTTING_DOWN: AtomicBool = AtomicBool::new(false);

extern "C" fn handle_signal(_: i32) {
    SHUTTING_DOWN.store(true, Ordering::Relaxed);
}

fn install_signal_handlers() -> nix::Result<()> {
    let action = SigAction::new(SigHandler::Handler(handle_signal), SaFlags::empty(), SigSet::empty());
    for signal_number in [Signal::SIGTERM, Signal::SIGINT, Signal::SIGQUIT] {
        unsafe { signal::sigaction(signal_number, &action)? };
    }
    unsafe { signal::sigaction(Signal::SIGPIPE, &SigAction::new(SigHandler::SigIgn, SaFlags::empty(), SigSet::empty()))? };
    Ok(())
}

fn reap_children() -> nix::Result<()> {
    loop {
        match waitpid(Pid::from_raw(-1), None) {
            Ok(WaitStatus::Exited(pid, status)) => {
                eprintln!("init: process {pid} exited with status {status}");
            }
            Ok(WaitStatus::Signaled(pid, signal, _)) => {
                eprintln!("init: process {pid} killed by signal {signal}");
            }
            Ok(_) => {}
            Err(Errno::EINTR) if SHUTTING_DOWN.load(Ordering::Relaxed) => return Ok(()),
            Err(Errno::EINTR) => continue,
            Err(Errno::ECHILD) => return Ok(()),
            Err(error) => return Err(error),
        }
    }
}

#[tokio::main]
async fn main() -> io::Result<()> {
    install_signal_handlers().map_err(io::Error::other)?;
    mount_kernel_filesystems("/".as_ref()).expect("init: failed to mount kernel filesystems");
    
    task::spawn(net::run());

    let child = fork_runtime().map_err(io::Error::other)?;

    while !SHUTTING_DOWN.load(Ordering::Relaxed) {
        reap_children().map_err(io::Error::other)?;
        if !SHUTTING_DOWN.load(Ordering::Relaxed) {
            pause();
        }
    }

    let _ = signal::kill(child, Signal::SIGTERM);
    reap_children().map_err(io::Error::other)
}
