package dev.ftcsim.runtime.fixture;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** What a program sees when the network is refused: the exception each kind of access fails with. */
public final class NetworkMessageProbe implements Callable<List<String>> {
    @Override
    public List<String> call() throws Exception {
        List<String> messages = new ArrayList<>();
        try {
            new ServerSocket(18080).close();
        } catch (IOException e) {
            messages.add(e.toString());
        }
        try {
            new Socket("example.invalid", 81).close();
        } catch (IOException e) {
            messages.add(e.toString());
        }
        try (DatagramSocket socket = new DatagramSocket((SocketAddress) null)) {
            socket.send(new DatagramPacket(new byte[1], 1, InetAddress.getByAddress(new byte[] {10, 0, 0, 1}), 18200));
        } catch (IOException e) {
            messages.add(e.toString());
        }
        return messages;
    }
}
