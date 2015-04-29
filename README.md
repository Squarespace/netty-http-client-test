
Context:

There appears to be a race condition in Netty 4.1.0-Beta w/ Epoll on Linux and this is a repro of it. 

* Client: Netty 4.1.0-Beta5-SNAPSHOT
* Server: Plain Java ServerSocket and Jetty (you can pick)

For each request a new connection is being created, the server responds and closes the connection.

Expected: The client consumes the response and closes the connection.

Actual: Every other connection gets closed before it consumes the response. Wireshark shows the response on the wire.

Steps to reproduce:

1. Clone this repository
2. ./run.sh

```
$ java -version
java version "1.8.0_40"
Java(TM) SE Runtime Environment (build 1.8.0_40-b25)
Java HotSpot(TM) 64-Bit Server VM (build 25.40-b25, mixed mode)
```

```
$ uname -a
Linux xxxxx 3.19.3-1-ARCH #1 SMP PREEMPT Thu Mar 26 14:56:16 CET 2015 x86_64 GNU/Linux
```

### Init

```
[main] DEBUG io.netty.channel.MultithreadEventLoopGroup - -Dio.netty.eventLoopThreads: 24
[main] DEBUG io.netty.util.NetUtil - Loopback interface: lo (lo, 0:0:0:0:0:0:0:1%lo)
[main] DEBUG io.netty.util.NetUtil - /proc/sys/net/core/somaxconn: 128
[main] DEBUG io.netty.channel.DefaultChannelId - -Dio.netty.processId: 4675 (auto-detected)
[main] DEBUG io.netty.channel.DefaultChannelId - -Dio.netty.machineId: 40:6c:8f:ff:fe:b9:25:55 (auto-detected)
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.numHeapArenas: 12
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.numDirectArenas: 12
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.pageSize: 8192
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.maxOrder: 11
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.chunkSize: 16777216
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.tinyCacheSize: 512
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.smallCacheSize: 256
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.normalCacheSize: 64
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.maxCachedBufferCapacity: 32768
[main] DEBUG io.netty.buffer.PooledByteBufAllocator - -Dio.netty.allocator.cacheTrimInterval: 8192
[main] DEBUG io.netty.buffer.ByteBufUtil - -Dio.netty.allocator.type: pooled
[main] DEBUG io.netty.buffer.ByteBufUtil - -Dio.netty.threadLocalDirectBufferSize: 65536
[epollEventLoopGroup-2-1] DEBUG io.netty.util.ResourceLeakDetector - -Dio.netty.leakDetectionLevel: simple
[main] DEBUG io.netty.util.Recycler - -Dio.netty.recycler.maxCapacity: 262144
```

### 1st request (success)

```
[main] TRACE com.squarespace.netty.NettyHttpClient - Sending Request: /jetty-netty-test-0 to [id: 0x16538249, /127.0.0.1:47837 => /127.0.0.1:8080]
[Thread-1] TRACE com.squarespace.netty.NettyHttpClient - [PlainServer] Received Request: GET /jetty-netty-test-0 HTTP/1.1 from Socket[addr=/127.0.0.1,port=47837,localport=8080]
[Thread-1] TRACE com.squarespace.netty.NettyHttpClient - [PlainServer] Sent Response: Socket[addr=/127.0.0.1,port=47837,localport=8080]
[epollEventLoopGroup-2-1] TRACE com.squarespace.netty.NettyHttpClient - Received Response: ChannelHandlerContext(NettyHttpClient$HttpHandler#0, [id: 0x16538249, /127.0.0.1:47837 => /127.0.0.1:8080])
```

### Nth request (failure)

```
[main] TRACE com.squarespace.netty.NettyHttpClient - Sending Request: /jetty-netty-test-1 to [id: 0x77e01dea, /127.0.0.1:47838 => /127.0.0.1:8080]
[Thread-2] TRACE com.squarespace.netty.NettyHttpClient - [PlainServer] Received Request: GET /jetty-netty-test-1 HTTP/1.1 from Socket[addr=/127.0.0.1,port=47838,localport=8080]
[Thread-2] TRACE com.squarespace.netty.NettyHttpClient - [PlainServer] Sent Response: Socket[addr=/127.0.0.1,port=47838,localport=8080]
[epollEventLoopGroup-2-2] ERROR com.squarespace.netty.NettyHttpClient - Connection closed without response: ChannelHandlerContext(NettyHttpClient$HttpHandler#0, [id: 0x77e01dea, /127.0.0.1:47838 :> /127.0.0.1:8080])
```

### Ignore

```
[Thread-0] TRACE com.squarespace.netty.NettyHttpClient - ServerSocket: Socket closed
```
