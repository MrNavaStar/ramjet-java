use std::io;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};

use nix::errno::Errno;
use nix::sys::signal::{self, SaFlags, SigAction, SigHandler, SigSet, Signal};
use nix::sys::wait::{waitpid, WaitStatus};
use nix::unistd::{fork, pause, ForkResult, Pid};

mod config;
mod runtime;

static SHUTTING_DOWN: AtomicBool = AtomicBool::new(false);

extern "C" fn handle_signal(_: i32) {
    SHUTTING_DOWN.store(true, Ordering::Relaxed);
}

fn install_signal_handlers() -> nix::Result<()> {
    let handler = SigHandler::Handler(handle_signal);
    let action = SigAction::new(handler, SaFlags::empty(), SigSet::empty());

    for signal_number in [Signal::SIGTERM, Signal::SIGINT, Signal::SIGQUIT] {
        unsafe { signal::sigaction(signal_number, &action)? };
    }

    let ignore_sigpipe = SigAction::new(SigHandler::SigIgn, SaFlags::empty(), SigSet::empty());
    unsafe { signal::sigaction(Signal::SIGPIPE, &ignore_sigpipe)? };
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

fn fork_runtime() -> nix::Result<Pid> {
    match unsafe { fork()? } {
        ForkResult::Parent { child } => Ok(child),
        ForkResult::Child => {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("init: failed to create runtime executor");
            let result = runtime.block_on(runtime::run(Path::new("/image"), Path::new("/root")));
            if let Err(error) = result {
                eprintln!("init: runtime failed: {error:#}");
                std::process::exit(1);
            }
            std::process::exit(0);
        }
    }
}

fn main() -> io::Result<()> {
    install_signal_handlers().map_err(io::Error::other)?;
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
