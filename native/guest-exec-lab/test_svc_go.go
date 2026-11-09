// An ordinary, unmodified Go runtime. No injected hooks, disabled preemption,
// cgo TLS assumptions, or dependence on the forwarding tracer.
package main

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"runtime"
	"runtime/debug"
	"runtime/pprof"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

//go:noinline
func grow(n int, seed uint64) uint64 {
	var frame [128]uint64
	for i := range frame { frame[i] = seed + uint64(i) }
	if n > 0 { seed = grow(n-1, seed+1) }
	return seed ^ frame[n%len(frame)]
}
var foreignWork = func(string) {}

func main() {
	if len(os.Args) == 2 && os.Args[1] == "--child" { return }
	if len(os.Args) < 2 || len(os.Args) > 3 { panic("directory and optional sandbox mode required") }
	runtime.GOMAXPROCS(4)
	debug.SetGCPercent(25)
	path := os.Args[1] + "/svc-go-data"
	const contents = "syscall-patch-probe"
	if err := os.WriteFile(path, []byte(contents), 0600); err != nil { panic(err) }
	defer os.Remove(path)
	var profile bytes.Buffer
	if err := pprof.StartCPUProfile(&profile); err != nil { panic(err) }
	ch := make(chan os.Signal, 64)
	signal.Notify(ch, syscall.SIGUSR1, syscall.SIGCHLD)
	defer signal.Stop(ch)
	var signals, operations atomic.Uint64
	done := make(chan struct{})
	go func() {
		for { select { case <-ch: signals.Add(1); case <-done: return } }
	}()
	var group sync.WaitGroup
	group.Add(1)
	go func() {
		defer group.Done()
		for i := 0; i < 128; i++ {
			if err := exec.Command(os.Args[0], "--child").Run(); err != nil { panic(err) }
		}
	}()
	for worker := 0; worker < 8; worker++ {
		group.Add(1)
		go func(id int) {
			defer group.Done()
			if id%2 == 0 { runtime.LockOSThread(); defer runtime.UnlockOSThread() }
			conn, err := net.ListenPacket("udp4", "127.0.0.1:0"); if err != nil { panic(err) }
			defer conn.Close()
			for i := 0; i < 600; i++ {
				if _, err := conn.WriteTo([]byte("probe"), conn.LocalAddr()); err != nil { panic(err) }
				var packet [16]byte
				if n, _, err := conn.ReadFrom(packet[:]); err != nil || string(packet[:n]) != "probe" { panic(err) }
				if st, err := os.Stat(path); err != nil || st.Size() != int64(len(contents)) { panic(fmt.Sprint(st, err)) }
				f, err := os.Open(path); if err != nil { panic(err) }
				data, err := io.ReadAll(f); if err != nil || string(data) != contents { panic(err) }
				if err := f.Close(); err != nil { panic(err) }
				_, _, errno := syscall.RawSyscall(syscall.SYS_GETPID, 0, 0, 0)
				if errno != 0 { panic(errno) }
				_ = grow(48, uint64(i))
				operations.Add(1)
				if i%100 == 0 { runtime.GC(); syscall.Kill(os.Getpid(), syscall.SIGUSR1) }
				if i%50 == 0 { foreignWork(path) }
				runtime.Gosched()
			}
		}(worker)
	}
	// This loop has no calls: CPU profiling and asynchronous preemption stay on.
	group.Add(1)
	go func() {
		defer group.Done()
		end := time.Now().Add(300*time.Millisecond)
		var value uint64 = 1
		for time.Now().Before(end) { for i:=0;i<2000000;i++ { value=value*6364136223846793005+1 } }
		if value==0 { panic("checksum") }
	}()
	group.Wait()
	pprof.StopCPUProfile()
	close(done)
	reader, err := gzip.NewReader(bytes.NewReader(profile.Bytes())); if err != nil { panic(err) }
	data, err := io.ReadAll(reader); if err != nil { panic(err) }; reader.Close()
	if signals.Load()==0 || operations.Load()!=4800 || len(data)<256 { panic("missing stress coverage") }
	fmt.Printf("PASS Go stress: calls=%d signals=%d profileBytes=%d GC=%d\n",operations.Load(),signals.Load(),len(data),readGC())
	if len(os.Args)==3 { sandbox(path) }
}

func readGC() uint32 { var st runtime.MemStats; runtime.ReadMemStats(&st); return st.NumGC }

func sandbox(path string) {
	runtime.LockOSThread()
	name, err := syscall.BytePtrFromString(path); if err != nil { panic(err) }
	var st syscall.Stat_t
	// Stock seccomp ABI: the same workload is denied, while an injected gettid
	// would kill the process. TSYNC includes all already-created Go threads.
	filter := []syscall.SockFilter{
		{Code:0x20, K:0},
		{Code:0x15, Jf:1, K:syscall.SYS_GETTID},
		{Code:0x06, K:0x80000000},
		{Code:0x15, Jf:1, K:syscall.SYS_FSTATAT},
		{Code:0x06, K:0x50000|uint32(syscall.EACCES)},
		{Code:0x06, K:0x7fff0000},
	}
	program := syscall.SockFprog{Len:uint16(len(filter)), Filter:&filter[0]}
	if _,_,e := syscall.RawSyscall6(syscall.SYS_PRCTL,38,1,0,0,0,0); e!=0 { panic(e) }
	if r,_,e := syscall.RawSyscall(syscall.SYS_SECCOMP,1,1,uintptr(unsafe.Pointer(&program))); e!=0 || r!=0 { panic(fmt.Sprint(r,e)) }
	for i:=0;i<4096;i++ {
		_,_,e := syscall.RawSyscall6(syscall.SYS_FSTATAT,^uintptr(99),uintptr(unsafe.Pointer(name)),uintptr(unsafe.Pointer(&st)),0,0,0)
		if e!=syscall.EACCES { syscall.RawSyscall(syscall.SYS_EXIT_GROUP,3,0,0) }
	}
	message:=[]byte("PASS Go sandbox: TSYNC errno=EACCES helper=gettid-kill calls=4096\n")
	syscall.RawSyscall(syscall.SYS_WRITE,1,uintptr(unsafe.Pointer(&message[0])),uintptr(len(message)))
	syscall.RawSyscall(syscall.SYS_EXIT_GROUP,0,0,0)
}
