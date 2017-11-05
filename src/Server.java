import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int TIMEOUT = 100; // Retransmission timeout (if ACK was not received)
    private static byte buf[];              // Buffer for incoming packets
    private static int seqNum;              // Sequence number
    private static int inAckNum;            // Incoming ACK number
    private static int outAckNum;           // Outcoming ACK number
    private static boolean connection;      // Connection is established
    private static int totalPackets;        // Total number of received packets
    private static int corrPackets;         // Total number of corrupted packets
    private static ByteArrayOutputStream data;

    private static DatagramSocket socket;
    private static DatagramPacket receivePacket;
    private static DatagramPacket sendPacket;

    public static void main(String[] args) {
        new Thread(() -> {
            try {
                // Create a server socket
                socket = new DatagramSocket(8000);
                System.out.println("Server has been started");

                // Initialize a buffer for incoming packets
                buf = new byte[1460];
                receivePacket = new DatagramPacket(buf, buf.length);

                // initialize other variables
                seqNum = 0;
                inAckNum = 0;
                outAckNum = 0;
                totalPackets = 0;
                corrPackets = 0;
                connection = false;

                // Server must be always online
                while(true){
                    // Initialize buffer for each iteration
                    Arrays.fill(buf, (byte)0);

                    // Receive a packet from the client
                    socket.receive(receivePacket);
                    parsePacket();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void parsePacket() throws IOException {
        socket.setSoTimeout(0); // reset timeout to infinity
        totalPackets++;

        MyDatagram dg = new MyDatagram(receivePacket.getData());

        if (dg.Verify()){
            // if a synchronization packet
            if (dg.SYN()) {
                outAckNum = ~dg.SeqNum();   // determine next packet to request
                connect();
            }
            // if an acknowledgement packet
            else if (dg.ACK())
                inAckNum = dg.ACKNum();
            // if connection has been established
            else if (connection) {
                // if packet with correct sequence number is received
                if (outAckNum == dg.SeqNum()) {
                    outAckNum = ~outAckNum;
                    // if a finalization packet
                    if (dg.FIN())
                        disconnect();
                    // if a packet with data
                    else {
                        System.out.println("Packet with sequence number " + dg.SeqNum() + " was received.");
                        // Save data
                        data.write(dg.GetData());
                        // Send ACK
                        SendPacket((byte) 0, (byte) 1, (byte) 0, 0, outAckNum, null);
                        // no need timer for outcoming ACKs
                        socket.setSoTimeout(0);
                    }
                }
                // if packet with incorrect sequence number - request needed packet
                else {
                    SendPacket((byte) 0, (byte) 1, (byte) 0, 0, outAckNum, null);
                    // no need timer for outcoming ACKs
                    socket.setSoTimeout(0);
                }
            }
        }
        else {
            System.out.println("Corrupted packet: drop.");
            corrPackets++;
        }
    }

    private static void connect() throws IOException {
        System.out.println("Synchronization...");
        // store transferred file
        data = new ByteArrayOutputStream();

        while (!connection) {
            // send SIN + ACK
            SendPacket((byte) 1, (byte) 1, (byte) 0, seqNum, outAckNum, null);

            // receive ACK
            try {
                Arrays.fill(buf, (byte)0);
                socket.receive(receivePacket);
                parsePacket();

                if(inAckNum == ~seqNum) {
                    System.out.println("Connection with the client has been established (IP address: "
                            + receivePacket.getAddress() + ", port number: " + receivePacket.getPort() + ")\n");
                    connection = true;
                    // update the sequence number
                    seqNum = ~seqNum;
                }
            } catch (SocketTimeoutException ex) {
                socket.send(sendPacket);
            }
        }
    }

    private static void disconnect() throws IOException {
        System.out.println("\nDisconnection...");
        // send ACK
        SendPacket((byte)0, (byte)1, (byte)0, 0, outAckNum, null);
        // send FIN
        SendPacket((byte)0, (byte)0, (byte)1, seqNum, 0, null);

        boolean disconnected = false;
        // receive ACK
        while(!disconnected) {
            try {
                Arrays.fill(buf, (byte)0);
                socket.receive(receivePacket);
                parsePacket();

                if(inAckNum == ~seqNum) {
                    System.out.println("The client has been disconnected (IP address: " + receivePacket.getAddress() +
                            ", port number: " + receivePacket.getPort() + ")");
                    System.out.println("Total number of received packets: " + totalPackets +
                            ". Number of corrupted packets: " + corrPackets + " (" +
                            String.format("%.02f%%).", ((corrPackets / (double)totalPackets) * 100)));

                    totalPackets = 0;
                    corrPackets = 0;
                    connection = false;
                    disconnected = true;

                    // write received data into a file
                    FileOutputStream file = new FileOutputStream("dcn.pdf");
                    file.write(data.toByteArray());
                    file.close();
                    data.close();

                    // update the sequence number
                    seqNum = ~seqNum;
                }
            } catch (SocketTimeoutException ex) {
                socket.send(sendPacket);
            }
        }
    }

    private static void SendPacket(byte SYN, byte ACK, byte FIN, int sn, int an, byte[] d) throws IOException {
        MyDatagram udp = new MyDatagram(SYN, ACK, FIN, sn, an, d);

        sendPacket = new DatagramPacket(udp.GetDatagram(), udp.GetDatagram().length,
                receivePacket.getAddress(), receivePacket.getPort());
        socket.send(sendPacket);
        socket.setSoTimeout(TIMEOUT);
    }
}
