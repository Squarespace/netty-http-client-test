package com.squarespace.netty;

import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

public class NettyHttpClient {
  
  private static final Logger LOG = LoggerFactory.getLogger(NettyHttpClient.class);
  
  private static final int HTTP_SERVER_PORT = 8080;
  
  private static final InetSocketAddress HTTP_SERVER_ADDRESS 
    = new InetSocketAddress("localhost", HTTP_SERVER_PORT);
  
  private static final boolean USE_EPOLL = true;
  
  @Test
  public void nettyHttpClient() throws Exception {
    
    try (HttpServer server = new PlainServer(HTTP_SERVER_PORT)) {
      server.start();
      
      Class<? extends SocketChannel> type  = null;
      EventLoopGroup group = null;
      
      if (USE_EPOLL) {
        type = EpollSocketChannel.class;
        group = new EpollEventLoopGroup();
      } else {
        type = NioSocketChannel.class;
        group = new NioEventLoopGroup();
      }
      
      try {
        for (int i = 0; i < 100; i++) {
          
          HttpHandler handler = new HttpHandler();
          
          Bootstrap bootstrap = new Bootstrap();
        
          bootstrap
            .channel(type)
            .group(group)
            .option(ChannelOption.AUTO_READ, true)
            .option(ChannelOption.SO_REUSEADDR, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
            .handler(newHttpHandler(handler));
          
          Channel connection = bootstrap.connect(HTTP_SERVER_ADDRESS)
              .sync()
              .channel();
          
          try {
            
            String path = "/jetty-netty-test-" + i;
            
            DefaultHttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, path);
            request.headers().set("Host", "localhost");
            request.headers().set("Connection", "close");
            
            if (LOG.isTraceEnabled()) {
              LOG.trace("Sending Request: {} to {}", path, connection);
            }
            
            connection.writeAndFlush(request).await();
            connection.read();
            
            if (!handler.await(5L, TimeUnit.SECONDS)) {
              fail("Should not have failed: index=" + i);
            }
            
          } finally {
            connection.close();
          }
        }
      } finally {
        group.shutdownGracefully();
      }
    }
  }
  
  private static ChannelHandler newHttpHandler(ChannelHandler handler) {
    return new ChannelInitializer<SocketChannel>() {
      @Override
      protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        
        pipeline.addLast(new HttpClientCodec());
        pipeline.addLast(handler);
      }
    };
  }
  
  private static class HttpHandler extends ChannelInboundHandlerAdapter {
    
    private final CountDownLatch latch = new CountDownLatch(1);
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      
      if (!isAutoRead(ctx)) {
        ctx.read();
      }
      
      super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      
      if (latch.getCount() != 0L) {
        if (LOG.isErrorEnabled()) {
          LOG.error("Connection closed without response: {}", ctx);
        }
      }
      
      super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      
      if (msg instanceof HttpResponse) {
        
        if (LOG.isTraceEnabled()) {
          LOG.trace("Received Response: {}", ctx);
        }
        
        latch.countDown();
      }
      
      if (!isAutoRead(ctx) && !(msg instanceof LastHttpContent)) {
        ctx.read();
      }
    }
    
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      return latch.await(timeout, unit);
    }
    
    private static boolean isAutoRead(ChannelHandlerContext ctx) {
      Channel channel = ctx.channel();
      ChannelConfig config = channel.config();
      
      return config.isAutoRead();
    }
  }
  
  /**
   * @see PlainServer
   * @see JettyServer
   */
  private static interface HttpServer extends AutoCloseable {
    
    public void start() throws Exception;
  }
  
  /**
   * {@link ServerSocket}
   */
  private static class PlainServer implements HttpServer {

    private final ServerSocket serverSocket;
    
    public PlainServer(int port) throws IOException {
      this.serverSocket = new ServerSocket(port);
    }
    
    @Override
    public void start() throws Exception {
      (new Thread() {
        @Override
        public void run() {
          while (!serverSocket.isClosed()) {
            try {
              Socket socket = serverSocket.accept();
              process(socket);
            } catch (Exception err) {
              
              if (serverSocket.isClosed() 
                  && err instanceof SocketException) {
                LOG.trace("ServerSocket: {}", err.getMessage());
                
              } else {
                LOG.error("Exception", err);
              }
            }
          }
        }
      }).start();
    }
    
    private void process(Socket socket) {
      (new Thread() {
        @Override
        public void run() {
          try {
            
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
              String line = null;
              for (int i = 0; (line = in.readLine()) != null; i++) {
                String normalized = line.trim();
                
                if (i == 0) {
                  if (LOG.isTraceEnabled()) {
                    LOG.trace("[PlainServer] Received Request: {} from {}", normalized, socket);
                  }
                }
                
                if (normalized.isEmpty()) {
                  break;
                }
              }
              
              StringBuilder sb = new StringBuilder();
              sb.append("HTTP/1.1 200 OK\r\n")
                .append("Connection: close\r\n")
                .append("\r\n");
              
              try (OutputStream out = socket.getOutputStream()) {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
              }
              
              if (LOG.isTraceEnabled()) {
                LOG.trace("[PlainServer] Sent Response: {}", socket);
              }
            }
            
          } catch (Exception err) {
            LOG.error("Exception: socket={}", socket, err);
            
          } finally {
            closeQuietly(socket);
          }
        }
      }).start();
    }
    
    @Override
    public void close() throws Exception {
      closeQuietly(serverSocket);
    }
    
    private static void closeQuietly(Closeable closeable) {
      try {
        closeable.close();
      } catch (Exception err) {
        LOG.error("Exception: closeable={}", closeable, err);
      }
    }
  }
  
  /**
   * Jetty!
   */
  private static class JettyServer implements HttpServer {
    
    private final Server server;
    
    public JettyServer(int port) {
      server = new Server(port);
      server.setHandler(newServlet());
    }
    
    @Override
    public void start() throws Exception {
      server.start();
    }
    
    @Override
    public void close() throws Exception {
      server.stop();
    }
    
    private static Handler newServlet() {
      return new AbstractHandler() {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
          
          if (LOG.isTraceEnabled()) {
            LOG.trace("[JettyServer] Received Request: {}", request.getRequestURI());
          }
          response.setStatus(HttpServletResponse.SC_OK);
          response.getWriter().println("Hello, Netty!");
          response.flushBuffer();
          
          baseRequest.setHandled(true);
          
          if (LOG.isTraceEnabled()) {
            LOG.trace("[JettyServer] Sent Response: {}", request.getRequestURI());
          }
        }
      };
    }
  }
}
