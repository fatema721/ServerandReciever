//
import java.io.*;
import java.net.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

public class ReceiveClient {

    //DatagramSocket that handles UDP communication
    private static DatagramSocket clientSocket = null; 
    private static DatagramSocket clientSocketForTermination = null; 
    static int packetSequenceNumber = 2000; // Sequence number for tracking packets
    static String receiverEmail = "";
    static InetAddress serverAddress = null;
    static int serverPort = 12345; //server port number

    public static void main(String[] args) throws ClassNotFoundException {

        Scanner scanner = new Scanner(System.in); // Scanner for user input.

        // Map to store partial packets from server
        Map<String, ByteArrayOutputStream> serverData = new HashMap<>();

        try {
            // Prompt the user to enter the server hostname

            clientSocket = new DatagramSocket();
            System.out.println("\nMail Client Starting on host: " + InetAddress.getLocalHost().getHostName() + "\n");

            //ask user for server hostname and chekc if its correct
            while (serverAddress == null) {
                System.out.print("Type the name of Mail server: ");
                String serverName = scanner.nextLine();
                System.out.println();
                try {
                    serverAddress = InetAddress.getByName(serverName);
                } catch (UnknownHostException e) {
                    System.out.println("Server name is not available. Please enter a valid server name.");
                }
            }

            // Initiate three-way handshake
            System.out.println("\nConnecting to the server...");

            //send SYN
            sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "SYN", "SYN packet ", true);
            packetSequenceNumber++;
            System.out.println("Sent SYN to server.");

            // Wait for the server's SYN-ACK response 
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);

            //check received packet and complete handshake
            PacketObject receivedPacket = GetPacketObject(receiveData);
            switch (receivedPacket.GetType()) {
                case "SYN-ACK" -> {
                    System.out.println("Received SYN-ACK from server.");
                    sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "ACK", "SYN-ACK  Received", true);
                    packetSequenceNumber++;
                    System.out.println("Sent ACK to server. Connection established!");
                }
                default -> {
                    System.out.println("Handshake failed. Expected SYN-ACK but received: " + receivedPacket.GetType());
                    return;
                }
            }

            // Add (shutdown hook) to intercept Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                HandleShutdown(); 
            }));

            // Prompt the user to enter their email address and validate it
            String emailPattern = "^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$";
            while (true) {
                System.out.print("Enter Recipient Mail: ");
                receiverEmail = scanner.nextLine();
                if (!Pattern.matches(emailPattern, receiverEmail)) { //// Validate email format
                    // If the email doesn't match the regex format
                    do {
                        System.out.println("501 Error: Invalid email format. Please enter a valid TO email.");
                        System.out.print("\nEnter Recipient Mail: ");
                        receiverEmail = scanner.nextLine();
                    } while (!Pattern.matches(emailPattern, receiverEmail)); // Repeated until valid format

                }

                sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "TO-MAIL-VALIDATE", receiverEmail, true);
                packetSequenceNumber++;

                // Wait for the server's response (wait for TO-MAIL-VALIDATE response)
                byte[] mailValidateDate = new byte[1024];
                receivePacket = new DatagramPacket(mailValidateDate, mailValidateDate.length);
                clientSocket.receive(receivePacket);

                PacketObject toMailValidationObject = GetPacketObject(mailValidateDate);
                if ("550ERROR".equals(toMailValidationObject.GetType())) {
                    System.out.println("550 Error - The Recipient mail '" + receiverEmail + "' is not available in the Mail server!. Please enter another..");
                } else {
                    System.out.println("The Recipient Email validated successfully!");
                    break;
                }
            }

            // Register the recipient email with the server
            System.out.println("Send Register request to server as: " + receiverEmail);
            sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "REGISTER", receiverEmail, true);
            packetSequenceNumber++;

            byte[] registerResponse = new byte[1024];
            receivePacket = new DatagramPacket(registerResponse, registerResponse.length);
            clientSocket.receive(receivePacket);

            PacketObject toMailValidationObject = GetPacketObject(registerResponse);
            if ("REGISTER-DONE".equals(toMailValidationObject.GetType())) {
                System.out.println(toMailValidationObject.GetMessage());
            } else {
                System.out.println("The Recipient Email cannot registered successfully!..exiting");
                return;
            }

            // Sync inbox with the server to download any undelivered emails
            System.out.println("Started Synching Inbox from the Server.. \nThis will fetch any mail which are not downloaded to the connected client machine..");
            String InboxItems = GetInboxItems(receiverEmail);

            System.out.println("Send SYNC-INBOX Request");

            sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "SYNC-INBOX", receiverEmail + "#" + InboxItems, true);
            boolean keepReceiving = true; // Control variable to keep the client running.
            InetAddress clientAddress = receivePacket.getAddress();
            int clientPort = receivePacket.getPort();
            String clientKey = clientAddress.getHostAddress() + ":" + clientPort;
            System.out.println("\nWaiting for email from the server...");

            while (keepReceiving) {

                // Receive an email message from the server.
                byte[] recievedData = new byte[1024 + 1024];
                receivePacket = new DatagramPacket(recievedData, recievedData.length);
                clientSocket.receive(receivePacket);

                PacketObject receivedPackage = GetPacketObject(recievedData);

                if (receivedPackage != null) {
                    switch (receivedPackage.GetType()) {
                        case "SYN-DONE" -> {
                            // Inbox synchronization complete
                            System.out.println("\nInbox Sync is completed from Server");
                            System.out.println("\nWaiting for email from the server...");
                        }
                        case "EMAIL" -> {

                            // Process incoming email packet
                            System.out.println("Received EMAIL packet from Server with packet sequence " + receivedPackage.GetSequenceNumber() + " ");

                            serverData.putIfAbsent(clientKey, new ByteArrayOutputStream());
                            ByteArrayOutputStream outputStream = serverData.get(clientKey);
                            byte[] mailData = receivedPackage.GetData();
                            try {
                                outputStream.write(mailData);
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            sendResponse(clientSocket, serverAddress, serverPort, receivedPackage.GetSequenceNumber(), "PKT-ACK", "Email chunk received from  Server with Packet Sequence number : " + receivedPackage.GetSequenceNumber() + " ");
                            if (receivedPackage.GetEndOfMessage() == true) {

                                // If it's the final packet, process the complete email
                                byte[] completeData = outputStream.toByteArray();
                                serverData.remove(clientKey); // Remove the client's data after processing  

                                ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(completeData);
                                ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);

                                // deserialize
                                MailObject deserializedMail = (MailObject) objectInputStream.readObject();

                                String to = deserializedMail.getRecipient().trim().toLowerCase();
                                String from = deserializedMail.getSender().trim().toLowerCase();
                                String subject = deserializedMail.getSubject().trim();
                                String body = deserializedMail.getBody();
                                int sequenceNumber = deserializedMail.GetEmailSequence();
                                byte[] attachmentData = deserializedMail.getAttachment();
                                String attachmentName = deserializedMail.getAttachmentName();

                                //Display  email details
                                System.out.println("Sequence Number: " + sequenceNumber);
                                System.out.println("TIME: " + new SimpleDateFormat("EEE. MMM d, yyyy HH:mm").format(new Date()));
                                System.out.println("\nMail Received from Server of Sequence Number " + sequenceNumber);
                                System.out.println("FROM: " + from);
                                System.out.println("TO: " + to);
                                System.out.println("Subject: " + subject);
                                System.out.println("Body:" + body);
                                if (attachmentData != null) {
                                    System.out.println("Attachment name is :" + attachmentName);
                                } else {

                                    System.out.println("No Attachment");
                                }
                                SaveEmailToDirectory(deserializedMail);

                                sendResponse(clientSocket, clientAddress, clientPort, receivedPackage.GetSequenceNumber(), "MAIL-OK", "" + sequenceNumber + "");

                                System.out.println("\nWaiting for email from the server...");

                            }
                        }

                    }
                }
            }
        } catch (IOException e) {
            // Print any exceptions that occur during execution.
            e.printStackTrace();
        } finally {
            // Close the scanner to release resources.
            scanner.close();
        }
    }


    //handles termination using Ctrl+C
        private static void HandleShutdown() {
            try {
                System.out.println("\nCaught Ctrl+C! to Terminate the connection..."); 
                //create new datagram to handke termination
                clientSocketForTermination = new DatagramSocket(); 
        
                System.out.println("Sending FIN Termination Packet to Server..."); 
                // Send the FIN packet to the server
                sendPacket(clientSocketForTermination, serverAddress, serverPort, 0, "FIN", receiverEmail, true); 
        
                System.out.println("Waiting for FIN-ACK Packet from Server..."); 
        
                //store the incoming FIN-ACK packet.
                byte[] terminationAckData = new byte[1024]; 
                DatagramPacket terminationPacket = new DatagramPacket(terminationAckData, terminationAckData.length); 
        
                // Receive the FIN-ACK packet from the server
                clientSocketForTermination.receive(terminationPacket); 
        
                PacketObject terminationPacketObject = GetPacketObject(terminationAckData); // deserialize 
                if ("FIN-ACK".equals(terminationPacketObject.GetType())) { // Check if the packet type is FIN-ACK.
                    System.out.println("FIN-ACK received from server.."); 
                    System.out.println("Sending ACK-ACK to server.."); 
                    // Send ACK-ACK 
                    sendPacket(clientSocketForTermination, serverAddress, serverPort, packetSequenceNumber, "ACK-ACK", receiverEmail, true); 
        
                    // Close the  client socket if it's not already closed.
                    if (clientSocket != null && !clientSocket.isClosed()) {
                        clientSocket.close();
                    }
        
                    // Close the termination socket if it's not already closed.
                    if (clientSocketForTermination != null && !clientSocketForTermination.isClosed()) {
                        clientSocketForTermination.close();
                    }
        
                    System.out.println("Connection closed and terminating...");
                }
            } catch (IOException e) {
                e.printStackTrace(); 
            }
        }
        

        //handles sending packets 
    private static void sendPacket(DatagramSocket clientSocket, InetAddress clientAddress, int clientPort, int sequenceNo, String type, String message, boolean isEndOfMessage) {

        //set data relevant to the email
        PacketObject packetObject = new PacketObject();
        packetObject.SetSequenceNumber(sequenceNo);
        packetObject.SetType(type);
        packetObject.SetMessage(message);
        packetObject.SetEndOfMessage(isEndOfMessage);

        //serializing
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(packetObject);
            objectOutputStream.flush();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        byte[] sendData = byteArrayOutputStream.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
        try {
            clientSocket.send(sendPacket); // Send the packet through the client socket.
        } catch (IOException e) {
            e.printStackTrace(); // Print any exception during packet sending.

        }

    }

    private static PacketObject GetPacketObject(byte[] receivedData) {
        //deserialize PacketObject
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(receivedData);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            PacketObject packetObject = (PacketObject) objectInputStream.readObject();
            return packetObject; 
        } catch (ClassNotFoundException | IOException e) {
            System.out.println(e);
            System.out.println("\nUnable to Deserialize the incoming packet.. ");
        }
        return null;
    }

    //sending responses to server
    private static void sendResponse(DatagramSocket serverSocket, InetAddress clientAddress, int clientPort, int sequenceNo, String type, String message) {

        //set relevant data
        PacketObject packetObject = new PacketObject();
        packetObject.SetSequenceNumber(sequenceNo);
        packetObject.SetType(type);
        packetObject.SetMessage(message);

        //serialize
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(packetObject);
            objectOutputStream.flush();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        byte[] sendData = byteArrayOutputStream.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);
        try {
            serverSocket.send(sendPacket); // Send the response packet.
        } catch (IOException e) {
            e.printStackTrace();

        }

    }


    //saving the email to the directory
    private static void SaveEmailToDirectory(MailObject mail) {

        File clientDirectory = new File("Client");
        File senderDirectory = new File("Client/" + mail.getRecipient());
        File outBoxDir = new File("Client/" + mail.getRecipient() + "/InBox");
        File sequenceDir = new File("Client/" + mail.getRecipient() + "/InBox/" + mail.GetEmailSequence());
        File emailDir = new File("Client/" + mail.getRecipient() + "/InBox/" + mail.GetEmailSequence() + "/Mail");
        File attachmentDir = new File("Client/" + mail.getRecipient() + "/InBox/" + mail.GetEmailSequence() + "/Attachment");

        if (!clientDirectory.exists()) {
            clientDirectory.mkdir(); //create the Client directory if it doesn't exist
        }
        if (!senderDirectory.exists()) {
            senderDirectory.mkdir(); //create the Outbox directory if it doesn't exist
        }
        if (!outBoxDir.exists()) {
            outBoxDir.mkdir(); //create the Outbox directory if it doesn't exist
        }
        if (!sequenceDir.exists()) {
            sequenceDir.mkdir(); //create the sequence directory if it doesn't exist
        }
        if (!emailDir.exists()) {
            emailDir.mkdir(); // Create the email directory if it doesn't exist
        }
        if (!attachmentDir.exists()) {
            attachmentDir.mkdir(); // Create the attachment directory if it doesn't exist
        }


        //printing content of the email
        String emailContent = "SEQ: " + mail.GetEmailSequence() + "\nTO: " + mail.getRecipient() + "\nFROM: " + mail.getSender() + "\nSubject: " + mail.getSubject() + "\nBody: " + mail.getBody();

        String timeStamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
        File emailFile = new File(emailDir, mail.getSubject() + "_" + timeStamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(emailFile))) {
            //savinf email
            writer.write(emailContent);
            System.out.println("Email content saved to: " + emailFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Error saving email to file: " + e.getMessage());
        }
        if (mail.getAttachment() != null) {
            File attachment = new File(attachmentDir, mail.getAttachmentName());
            try (FileOutputStream out = new FileOutputStream(attachment)) {
                out.write(mail.getAttachment());
                System.out.println("Email Attachment saved to: " + attachment.getAbsolutePath());
            } catch (IOException e) {
                System.out.println("Error saving email attachment: " + e.getMessage());
            }
        }
    }

    private static String GetInboxItems(String receiverEmail) {
        String returnString = "";
        List<String> folders = new ArrayList<>();
        // Define the path of the parent directory
        Path folderPath = Paths.get("Client/" + receiverEmail + "/InBox");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
            // Iterate through the directory and print subfolders
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    folders.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {

        }
        for (String string : folders) {
            returnString = returnString + ";" + string;
        }
        return returnString;

    }

}
