package main

import (
	"os"
	"os/exec"
	"os/signal"
	"syscall"
)

type processResult struct {
	process *exec.Cmd
	err     error
}

func main() {
	containerd := exec.Command("/usr/local/bin/containerd", "--config", "/etc/containerd/config.toml")
	containerd.Stdout = os.Stdout
	containerd.Stderr = os.Stderr
	if err := containerd.Start(); err != nil {
		panic(err)
	}

	kubelet := exec.Command("/usr/local/bin/kubelet", os.Args[1:]...)
	kubelet.Stdout = os.Stdout
	kubelet.Stderr = os.Stderr
	if err := kubelet.Start(); err != nil {
		_ = containerd.Process.Signal(syscall.SIGTERM)
		panic(err)
	}

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT, syscall.SIGQUIT)
	results := make(chan processResult, 2)
	go func() { results <- processResult{kubelet, kubelet.Wait()} }()
	go func() { results <- processResult{containerd, containerd.Wait()} }()

	select {
	case <-signals:
		_ = kubelet.Process.Signal(syscall.SIGTERM)
		_ = containerd.Process.Signal(syscall.SIGTERM)
	case result := <-results:
		_ = result.process
		_ = kubelet.Process.Signal(syscall.SIGTERM)
		_ = containerd.Process.Signal(syscall.SIGTERM)
	}

	<-results
}
