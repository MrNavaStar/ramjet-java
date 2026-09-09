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
	crio := exec.Command("/usr/local/bin/crio", "--config", "/etc/crio/crio.conf")
	crio.Stdout = os.Stdout
	crio.Stderr = os.Stderr
	if err := crio.Start(); err != nil {
		panic(err)
	}

	kubelet := exec.Command("/usr/local/bin/kubelet", os.Args[1:]...)
	kubelet.Stdout = os.Stdout
	kubelet.Stderr = os.Stderr
	if err := kubelet.Start(); err != nil {
		_ = crio.Process.Signal(syscall.SIGTERM)
		panic(err)
	}

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGTERM, syscall.SIGINT, syscall.SIGQUIT)
	results := make(chan processResult, 2)
	go func() { results <- processResult{kubelet, kubelet.Wait()} }()
	go func() { results <- processResult{crio, crio.Wait()} }()

	select {
	case <-signals:
		_ = kubelet.Process.Signal(syscall.SIGTERM)
		_ = crio.Process.Signal(syscall.SIGTERM)
	case result := <-results:
		_ = result.process
		_ = kubelet.Process.Signal(syscall.SIGTERM)
		_ = crio.Process.Signal(syscall.SIGTERM)
	}

	<-results
}
