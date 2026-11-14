package dev.ftcsim.runtime.fixture;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Talks to itself over the loopback interface only, through every kind of socket the policy can allow. */
public final class LoopbackProbe implements Callable<List<String>> {
    @Override
    public List<String> call() throws Exception {
        List<String> log = new ArrayList<>();
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});

        try (ServerSocket server = new ServerSocket(0, 5, loopback);
                Socket client = new Socket(loopback, server.getLocalPort());
                Socket accepted = server.accept()) {
            client.getOutputStream().write(7);
            log.add("socket constructors " + accepted.getInputStream().read());
        }

        try (ServerSocket server = new ServerSocket();
                Socket client = new Socket()) {
            server.bind(new InetSocketAddress(loopback, 0));
            client.connect(new InetSocketAddress(loopback, server.getLocalPort()), 2000);
            try (Socket accepted = server.accept()) {
                client.getOutputStream().write(9);
                log.add("bind and connect " + accepted.getInputStream().read());
            }
        }

        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress(loopback, 0));
            int port = ((InetSocketAddress) server.getLocalAddress()).getPort();
            try (SocketChannel client = SocketChannel.open(new InetSocketAddress(loopback, port));
                    SocketChannel accepted = server.accept()) {
                client.write(ByteBuffer.wrap(new byte[] {3}));
                ByteBuffer received = ByteBuffer.allocate(1);
                accepted.read(received);
                log.add("channels " + received.get(0));
            }
        }

        try (DatagramSocket receiver = new DatagramSocket(0, loopback);
                DatagramSocket sender = new DatagramSocket(0, loopback)) {
            sender.send(new DatagramPacket(new byte[] {5}, 1, loopback, receiver.getLocalPort()));
            DatagramPacket received = new DatagramPacket(new byte[1], 1);
            receiver.setSoTimeout(2000);
            receiver.receive(received);
            log.add("datagrams " + received.getData()[0]);
        }
        return log;
    }
}
