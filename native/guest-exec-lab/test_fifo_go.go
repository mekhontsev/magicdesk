// Stock Go FIFO opens, proc-fd handles and runtime netpoll under concurrent IO.
package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"runtime"
	"sync"
	"syscall"
	"time"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	runtime.GOMAXPROCS(4)
	root, err := os.MkdirTemp("", "md-fifo-go-")
	check(err)
	defer os.RemoveAll(root)
	done := make(chan struct{})
	go func() {
		select {
		case <-done:
		case <-time.After(60 * time.Second): // EVENT_WAIT: missing peer/data/EOF fails the test.
			panic("FIFO transfer deadline")
		}
	}()
	var workers sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		workers.Add(1)
		go func(worker int) {
			defer workers.Done()
			payload := bytes.Repeat([]byte{byte(worker)}, 65536)
			for round := 0; round < 64; round++ {
				path := fmt.Sprintf("%s/%d-%d", root, worker, round)
				check(syscall.Mkfifo(path, 0600))
				pin, err := syscall.Open(path, 0x200000|syscall.O_CLOEXEC, 0) // O_PATH
				check(err)
				proc := fmt.Sprintf("/proc/self/fd/%d", pin)
				received := make(chan struct{})
				go func() {
					file, err := os.OpenFile(proc, os.O_RDONLY, 0)
					check(err)
					data, err := io.ReadAll(file)
					check(err)
					check(file.Close())
					if !bytes.Equal(data, payload) {
						panic(fmt.Sprintf("FIFO %s: received %d bytes", path, len(data)))
					}
					close(received)
				}()
				file, err := os.OpenFile(proc, os.O_WRONLY, 0)
				check(err)
				n, err := file.Write(payload)
				check(err)
				if n != len(payload) {
					panic("short FIFO write")
				}
				check(file.Close())
				<-received
				check(syscall.Close(pin))
				check(os.Remove(path))
			}
		}(worker)
	}
	workers.Wait()
	close(done)
	fmt.Println("PASS Go FIFO: 512 concurrent proc-fd opens, native IO, netpoll and EOF")
}
