import java.io.*;
import java.net.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;

public class Server {

    //maping the active clients with their ip address and port number
    private static final Map<String, InetSocketAddress> activeReceivers = new HashMap<>();

    // Map to store partial packets from clients -- Stores fragmented packets from clients until all parts are received.
    private static final Map<String, ByteArrayOutputStream> clientData = new HashMap<>();
    private static DatagramSocket serverSocket = null;
    private static final String[] validEmails = {"a@gmail.com", "m@gmail.com", "f@gmail.com"}; //list of valid emails

    public static void main(String[] args) {

        try {
            // Map to store partial packets from clients
            serverSocket = new DatagramSocket(12345);
            byte[] receiveData = new byte[1024 + 1024];

            //print the servers hostname
            System.out.println("\nMail Server Starting at host: " + InetAddress.getLocalHost().getHostName());
            System.out.println("\nWaiting for communication packets from clients...");

            //this would handle incomming packets from the clients
            while (true) {
                //creating a place where the data from the client and their ip address and port number are saved
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                //storing the data 
                serverSocket.receive(receivePacket);

                //get the clients information using receivePacket
                //fitch the data from the receivePacket
                InetAddress clientAddress = receivePacket.getAddress();
                int clientPort = receivePacket.getPort();
                String clientKey = clientAddress.getHostAddress() + ":" + clientPort;
                byte[] packetData = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());

                //get the data from the receivers packet
                PacketObject receivedPacket = GetPacketObject(packetData);
                //handeling new packets 
                if (receivedPacket != null) {
                    //use the method HandleReceivedPacket which is goinfg to handle data
                    HandleReceivedPacket(receivedPacket, clientAddress, clientPort, clientKey);
                    System.out.println("Waiting for communication packets from clients...");
                }
            }
        } catch (IOException e) { //handling errors
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    //this method takes the row data (bytes) received from clients and converts it into objects (explained in the report)
    private static PacketObject GetPacketObject(byte[] receivedData) {
        //read the received byte array
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(receivedData);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {

            //Read and deserialize the byte stream into a PacketObject.
            // Converts the raw byte data back into a PacketObject instance.
            PacketObject packetObject = (PacketObject) objectInputStream.readObject();
            return packetObject;
        } catch (ClassNotFoundException | IOException e) { //handling errors
            System.out.println("\nUnable to Deserialize the incoming packet.. ");
        }
        return null;
    }

    // handles different messages received from the clients
    private static void HandleReceivedPacket(PacketObject packetObject, InetAddress clientAddress, int clientPort, String clientKey) {

        switch (packetObject.GetType()) {
            case "SYN" -> { //if the packet contains SYN then print a statement and go to the method (handleHandshake)
                System.out.println("Received SYN packet from " + clientKey);//printing at the server side
                //use method "handleHandshake" to handle the handshake and send the a SYN-ACK to the client and print a message at the client side
                handleHandshake(serverSocket, clientAddress, clientPort, "Received SYN from client. Sending SYN-ACK...", "SYN-ACK", "SYN-ACK Success");
                System.out.println("Sent SYN-ACK packet to " + clientKey);//print that a SYN-ACK message was sent
            }
            case "ACK" -> //when the client sends an ACK packet then the handshake is successful
                System.out.println("Received ACK from client. 3 Way Handshake completed!");
            case "REGISTER" -> {//registering the receiver email adderss with the server
                System.out.println("Received REGISTER packet from " + clientKey);
                RegisterReceiver(packetObject, clientAddress, clientPort);
                System.out.println("Sent REGISTER-DONE packet to " + clientKey);
            }
            case "SYNC-INBOX" -> { //for syncing the emails (in receiver side)
                System.out.println("Received SYNC-INBOX packet from " + clientKey);
                SyncClientInbox(packetObject, clientAddress, clientPort, clientKey); //use the method to handle the syncing part
                System.out.println("Received SYNC-DONE packet to " + clientKey);
            }
            case "EMAIL" -> { //when receiving a new email from clients
                //print that an EMAIL packet has been received from the specified client.
                System.out.println("Received EMAIL packet from " + clientKey + " with packet sequence " + packetObject.GetSequenceNumber() + " ");
                //call handleemail
                // This method handles storing or reassembling the email data received in chunks.
                HandleEmail(clientAddress, clientPort, clientKey, packetObject);
                System.out.println("Sent PKT-ACK packet to " + clientKey + " with packet sequence " + packetObject.GetSequenceNumber() + " ");
                if (packetObject.GetEndOfMessage()) {//Check if this is the last packet of the email message.
                    // If true, print that a MAIL-COMP (Mail Complete) message has been sent to the client,
                    // indicating the entire email has been received and processed.
                    System.out.println("Sent MAIL-COMP packet to " + clientKey + " with packet sequence " + packetObject.GetSequenceNumber() + " ");
                }
            }
            case "FROM-MAIL-VALIDATE" -> {//Validate the sender's email address
            // print that a sender email validation request has been received from the client.
                System.out.println("Received FROM-MAIL-VALIDATE packet from " + clientKey);
                //call a method thats going to validate the email address 
                ValidateMailAddress(clientAddress, clientPort, clientKey, packetObject);
            }
            case "TO-MAIL-VALIDATE" -> {// Validate the recipient's email address
                System.out.println("Received TO-MAIL-VALIDATE packet from " + clientKey);
                // call a method thats going to validate the email address and sends an appropriate response to the client.
                ValidateMailAddress(clientAddress, clientPort, clientKey, packetObject);
            }
            case "GET-SEQ" -> { // Handles a request to get the next email sequence number
                System.out.println("Received GET-SEQ packet from " + clientKey + " for recipient " + packetObject.GetMessage() + " ");
                // Call the method to handle the sequence number request.
                // It calculates the next sequence number for the recipient and sends it back to the client.
                handleGetSequenceRequest(packetObject, clientAddress, clientPort);
                System.out.println("Sent GET-SEQ-DONE packet to " + clientKey + " for recipient " + packetObject.GetMessage() + " ");
            }
            case "FIN" -> {//when terminating
                System.out.println("Received FIN Termination packet from " + clientKey + " for client " + packetObject.GetMessage() + " ");
                // Call the method to handle termination.
                // It sends a FIN-ACK response to confirm the termination request.
                HandleFIN(packetObject, clientAddress, clientPort);
                System.out.println("Sent FIN-ACK packet to " + clientKey + " for client " + packetObject.GetMessage() + " ");
            }
            case "ACK-ACK" -> { // Final acknowledgment, confirming client disconnection
                // Check if the email in the packet is currently registered as active.
                if (activeReceivers.containsKey(packetObject.GetMessage().toLowerCase())) {
                    System.out.println("De-registering  client email: " + packetObject.GetMessage().toLowerCase() + " from the server..");
                    // Remove the email from the active receivers list.
                    activeReceivers.remove(packetObject.GetMessage().toLowerCase());
                    // print that the client's email has been successfully de-registered.
                    System.out.println("De-registered  client email: " + packetObject.GetMessage().toLowerCase() + " from the server..");
                }
                // print that the final acknowledgment packet has been received and the client is disconnected.
                System.out.println("Received ACK-ACK Termination packet from " + clientKey + " for client " + packetObject.GetMessage() + " and disconnected! ");

            }
            default ->
                System.out.println("Received : " + packetObject.GetType() + "!");
        }
    }

    //this method is used to validate the email addresses and sends a respond to the client
    private static void ValidateMailAddress(InetAddress clientAddress, int clientPort, String clientKey, PacketObject packetObject) {
        // Check if the email provided in the packet is valid by comparing it to a list of valid emails.
        if (isValidEmail(packetObject.GetMessage(), validEmails)) {
            // If the email is valid, send a "200 OK" response back to the client.
            sendResponse(serverSocket, clientAddress, clientPort, packetObject.GetSequenceNumber(), "200OK", "Valid Email");
            System.out.println("Sent 200OK packet to " + clientKey);
        } else {//if not send 550 Error
            sendResponse(serverSocket, clientAddress, clientPort, packetObject.GetSequenceNumber(), "550ERROR", "In-Valid Email");
            System.out.println("Sent 550ERROR packet to " + clientKey);
        }
    }

        //The method handles incoming email data sent by the client in chunks (small pieces).
    //It assembles the chunks into a complete email, processes it, and sends acknowledgments back to the client.
    private static void HandleEmail(InetAddress clientAddress, int clientPort, String clientKey, PacketObject packetObject) {

        //this line checks if clientKey (which is an id of the client) is not already in the map (clientData), it will add an empty container for that client.
        clientData.putIfAbsent(clientKey, new ByteArrayOutputStream());
       //Retrieve the Byte array output stream associated with the client from the clientData map
        ByteArrayOutputStream outputStream = clientData.get(clientKey);
        //Extracting the actual email data 
        byte[] mailData = packetObject.GetData();
        try {//Writeing the email data packets to the outputStream
            outputStream.write(mailData);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //send ACK back to client
        sendResponse(serverSocket, clientAddress, clientPort, packetObject.GetSequenceNumber(), "PKT-ACK", "Email chunk received from " + clientAddress + ":" + clientPort + " with Packet Sequence number : " + packetObject.GetSequenceNumber() + " ");
        if (packetObject.GetEndOfMessage() == true) {

            byte[] completeData = outputStream.toByteArray();
            clientData.remove(clientKey); // Remove the client's data after processing 
            processEmail(completeData, clientAddress, clientPort, validEmails, serverSocket);
            sendResponse(serverSocket, clientAddress, clientPort, packetObject.GetSequenceNumber(), "MAIL-COMP", "");
        }
    }


    //this method Finds the next available sequence number for emails in the recipient's inbox.
    private static void handleGetSequenceRequest(PacketObject packetObject, InetAddress clientAddress, int clientPort) {

        // Extract the recipient's email address from the packet object
        String recipient = packetObject.GetMessage();
        int sequence = 0;
        Path folderPath = Paths.get("Emails/" + recipient + "/InBox");
        
        //iterate through all directories in the inbox folder
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
            for (Path entry : stream) { // Loop through each entry in the folder

                //determine the largest sequence number
                if (Files.isDirectory(entry)) { 
                    int dirName = Integer.parseInt(entry.getFileName().toString());
                    if (dirName > sequence) {
                        sequence = dirName;
                    }
                }
            }
        } catch (IOException e) {

        }
        //Define the path to the client delivered directory
        folderPath = Paths.get("Emails/" + recipient + "/Delivered");
        
        //iterate through all directories and determine the latest sequence
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    int dirName = Integer.parseInt(entry.getFileName().toString());
                    if (dirName > sequence) {
                        sequence = dirName;
                    }
                }
            }
        } catch (IOException e) {

        }
        sequence++;
        sendResponse(serverSocket, clientAddress, clientPort, packetObject.sequenceNumber, "GET-SEQ-DONE", "" + sequence + "");
    }


    //sends FIN-ACK to the client
    private static void HandleFIN(PacketObject packetObject, InetAddress clientAddress, int clientPort) {

        sendResponse(serverSocket, clientAddress, clientPort, packetObject.sequenceNumber, "FIN-ACK", "Termination Acknowledged.");

    }


    //Registers a client (receiver) with their email and connection details.
    private static void RegisterReceiver(PacketObject packetObject, InetAddress clientAddress, int clientPort) {
        String email = packetObject.GetMessage();
        activeReceivers.put(email.toLowerCase(), new InetSocketAddress(clientAddress, clientPort));
        System.out.println("Receiver registered with email: " + email);
        sendResponse(serverSocket, clientAddress, clientPort, packetObject.sequenceNumber, "REGISTER-DONE", "Registration of Recipient '" + email + "' Completed.");
    }


    //Sync a client’s inbox by sending emails not already downloaded.
    private static void SyncClientInbox(PacketObject packetObject, InetAddress clientAddress, int clientPort, String clientKey) {

        String[] messageParts = packetObject.GetMessage().split("#"); //the data is separated with a # so now it has to be splited
        String EmailToSync = messageParts[0];
        String Excludes = "";

        if (messageParts.length == 2) {
            Excludes = messageParts[1];
        }
        String[] ExcludedMails = Excludes.split(";"); //split the emails
        List<String> InboxItems = GetInboxItems(EmailToSync); //all emails at the receivers inbox
        List<String> MailsToSync = new ArrayList<>(); //list of emails to sync
        Boolean found;

        //loop through all emails and check if any of them is not at the receivers inbox, if not then sync it
        for (String inboxItem : InboxItems) {
            found = false;
            for (String excludeMail : ExcludedMails) {
                if (inboxItem.trim().equals(excludeMail.trim())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                MailsToSync.add(inboxItem);
            }
        }

      // Check if there are emails to sync
      if (MailsToSync.isEmpty()) {
        // No emails to sync, notify the client that the inbox is already synchronize
        System.out.println("Inbox is already Synchronized. No new emails found!");
        sendResponse(serverSocket, clientAddress, clientPort, packetObject.sequenceNumber, "SYN-DONE", "Inbox is already Synchronized. No new emails found!");
    } else {
        System.out.println(MailsToSync.size() + " email(s) Found to Sync...");
        // Counter to track the number of synced emails
        int counter = 1; 
        for (String mailToSync : MailsToSync) {

            // Retrieve the full email data for the current email to sync
            MailObject mail = GetInboxItem(EmailToSync, mailToSync);
            //serialization part
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
                objectOutputStream.writeObject(mail);
                objectOutputStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Convert the serialized email object into a byte array
            byte[] sendData = byteArrayOutputStream.toByteArray();
            System.out.println(counter + "of " + MailsToSync.size() + " email(s) started to Sync..");
            // Forward the email data to the  receiver
            forwardEmailIfReceiverConnected(EmailToSync, sendData, serverSocket, clientAddress, clientPort);
            System.out.println(counter + "of " + MailsToSync.size() + " email(s) completed  Sync..");
            counter++;
        }
        //sync is done
        System.out.println(MailsToSync.size() + " email(s) Sync Completed");
        sendResponse(serverSocket, clientAddress, clientPort, packetObject.sequenceNumber, "SYN-DONE", "Inbox Synchronization completed.");
    }
}


    //this included all info about the email 
    // This method retrieves all information about the email, including its content and attachments
    private static MailObject GetInboxItem(String syncMail, String mailToSync) {


        MailObject mail; //ho;d emails details
        String mailFileName = ""; //store the email file name
        String attachmentFilename = ""; //store the attachment file name
        byte[] attachment = null; //hold the attachment data

        //directory path where the email content is stored
        File emailFolder = new File("Emails/" + syncMail + "/InBox/" + mailToSync + "/Mail");
        File[] mailFiles = emailFolder.listFiles();
        if (mailFiles != null) { // Check if the folder is not empty
            for (File file : mailFiles) {
                // Check if it's a file (not a directory)
                if (file.isFile()) { //if its a file retrieve the file name
                    mailFileName = file.getName();
            }
        }
    }
        String From = "";
        String To = "";
        String Subject = "";
        String Body = "";
        int Sequence = 0;
        // If the email file name is not empty, read the email content
        if (!"".equals(mailFileName)) {
            Path path = Path.of("Emails/" + syncMail + "/InBox/" + mailToSync + "/Mail/" + mailFileName);
            try {
                String content = Files.readString(path);
                String[] mailItems = content.split("\n");// Split the contentinto lines

                // get the email details from each line
                Sequence = Integer.parseInt(mailItems[0].replace("SEQ: ", "").trim());
                To = mailItems[1].replace("TO: ", "");
                From = mailItems[2].replace("FROM: ", "");
                Subject = mailItems[3].replace("Subject: ", "");
                Body = mailItems[4].replace("Body: ", "");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

            // Define the directory path where attachments are stored
            File AttachmentFolder = new File("Emails/" + syncMail + "/InBox/" + mailToSync + "/Attachment");
            File[] attachmentFiles = AttachmentFolder.listFiles(); // List all files in the Attachment directories

            if (attachmentFiles != null) { //check if directory is empty or not
                for (File file : attachmentFiles) { //if its a file
                    if (file.isFile()) {
                        attachmentFilename = file.getName();  // get the attachment file name
                    }
                }
            }

            // If the attachment file name is not empty, read the attachment data
        if (!"".equals(attachmentFilename)) {
            File file = new File("Emails/" + syncMail + "/InBox/" + mailToSync + "/Attachment/" + attachmentFilename);
            if (!file.exists() || file.isDirectory()) {
                System.out.println("Invalid file path.!.");
            } else {
                attachment = new byte[(int) file.length()]; // Initialize the byte array with the file size
                try (FileInputStream fis = new FileInputStream(file)) {
                    fis.read(attachment);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
        mail = new MailObject(Sequence, From, To, Subject, Body, attachment, attachmentFilename);
        return mail;
    }

    private static List<String> GetInboxItems(String receiverEmail) {

        List<String> folders = new ArrayList<>();
        Path folderPath = Paths.get("Emails/" + receiverEmail + "/InBox");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folderPath)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    folders.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {

        }

        return folders;

    }



    //this method handles handshake between clients
    private static void handleHandshake(DatagramSocket serverSocket, InetAddress clientAddress, int clientPort, String printMessage, String handShakeMessage, String message) {

        System.out.println(printMessage);
        PacketObject packetObject = new PacketObject();
        packetObject.SetSequenceNumber(1);
        packetObject.SetType(handShakeMessage);
        packetObject.SetMessage(message);

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
            serverSocket.send(sendPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void processEmail(byte[] completeData, InetAddress clientAddress, int clientPort, String[] validEmails, DatagramSocket serverSocket) {
        try {

            // Deserialize the MailObject from the byte array
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(completeData);
            ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream);

            // Read the object from the stream
            MailObject deserializedMail = (MailObject) objectInputStream.readObject();

            String to = deserializedMail.getRecipient().trim().toLowerCase();
            String from = deserializedMail.getSender().trim().toLowerCase();
            String subject = deserializedMail.getSubject().trim();
            String body = deserializedMail.getBody();
            int emailSequence = deserializedMail.GetEmailSequence();
            byte[] attachmentData = deserializedMail.getAttachment();
            String attachmentName = deserializedMail.getAttachmentName();

            // Debug: Display parsed email details
            System.out.println("\nMail Received from client at " + clientAddress + ":" + clientPort + " with  Email Sequence Number " + emailSequence);
            System.out.println("\nFROM: " + from);
            System.out.println("\nTO: " + to);
            System.out.println("\nSubject: " + subject);
            System.out.println("\nTIME: " + new SimpleDateFormat("EEE. MMM d, yyyy HH:mm").format(new Date()));
            System.out.println("\nBody:" + body);
            if (attachmentData != null) {
                System.out.println("\nAttachment :" + attachmentName);
            } else {
                System.out.println("No Attachment");
            }
            System.out.println("\n\n");
            // Validate and save the email
            if (isValidEmail(to, validEmails) && isValidEmail(from, validEmails)) {

                SaveEmailToFolder(deserializedMail, deserializedMail.getRecipient());

                // Send acknowledgment for successful processing
                String timestamp = new SimpleDateFormat("EEE. MMM d, yyyy HH:mm").format(new Date());
                String confirmation = "Email received successfully at " + timestamp + "- 250 OK of Email Sequence Number " + emailSequence;
                sendResponse(serverSocket, clientAddress, clientPort, 0, "250OK1", confirmation);
                forwardEmailIfReceiverConnected(to, completeData, serverSocket, clientAddress, clientPort, deserializedMail);
                System.out.println("The header fields are verified for Email Sequence Number " + emailSequence + "\nSending 250 OK response for Email Sequence Number " + emailSequence + ".");
                sendResponse(serverSocket, clientAddress, clientPort, 0, "250OK2", "The header fields are verified.");

            } else {
                sendResponse(serverSocket, clientAddress, clientPort, 0, "550ERROR", "Invalid email addresses for Email Sequence " + emailSequence);
                System.out.println("The header fields are not valid. Email rejected for Email Sequence " + emailSequence);
            }
            System.out.println("\nCompleted the processing of mail from client at " + clientAddress + ":" + clientPort + " with  Email Sequence Number " + emailSequence);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private static void SaveEmailToFolder(MailObject mail, String email) throws FileNotFoundException, IOException {

        File serverDirectory = new File("Emails");
        File senderDirectory = new File("Emails/" + email);
        File outBoxDir = new File("Emails/" + email + "/InBox");
        File sequenceDir = new File("Emails/" + email + "/InBox/" + mail.GetEmailSequence());
        File emailDir = new File("Emails/" + email + "/InBox/" + mail.GetEmailSequence() + "/Mail");
        File attachmentDir = new File("Emails/" + email + "/InBox/" + mail.GetEmailSequence() + "/Attachment");

        if (!serverDirectory.exists()) {
            serverDirectory.mkdir(); //create the Server Inbox directory if it doesn't exist
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

        String emailContent = "SEQ: " + mail.GetEmailSequence() + "\nTO: " + mail.getRecipient() + "\nFROM: " + mail.getSender() + "\nSubject: " + mail.getSubject() + "\nBody: " + mail.getBody();

        String timeStamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
        // Specify the file name based on the sequence number
        File emailFile = new File(emailDir, mail.getSubject() + "_" + timeStamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(emailFile))) {
            writer.write(emailContent);
            System.out.println("Email content saved in Server at : " + emailFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Error saving email to file: " + e.getMessage());
        }
        if (mail.getAttachment() != null) {
            File attachment = new File(attachmentDir, mail.getAttachmentName());
            try (FileOutputStream out = new FileOutputStream(attachment)) {
                out.write(mail.getAttachment());
                System.out.println("Email Attachment saved in Serve at: " + attachment.getAbsolutePath());
            } catch (IOException e) {
                System.out.println("Error saving email attachment: " + e.getMessage());
            }
        }
    }

    private static boolean isValidEmail(String email, String[] validEmails) {
        for (String validEmail : validEmails) {
            if (validEmail.equalsIgnoreCase(email)) {
                return true;
            }
        }
        return false;
    }


    //sending response to clients by
    private static void sendResponse(DatagramSocket serverSocket, InetAddress clientAddress, int clientPort, int sequenceNo, String type, String message) {

        PacketObject packetObject = new PacketObject();
        packetObject.SetSequenceNumber(sequenceNo); // Set sequence number
        packetObject.SetType(type); // Set the type of response
        packetObject.SetMessage(message);// Add the message content to the response


    
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
            serverSocket.send(sendPacket); // Send the response packet to the client
        } catch (IOException e) {
            e.printStackTrace();

        }

    }

    private static void forwardEmailIfReceiverConnected(String to, byte[] emailData, DatagramSocket serverSocket, InetAddress clientAddress, int clientPort, MailObject mail) {
        try {
            InetSocketAddress receiverAddress = activeReceivers.get(to);
            if (receiverAddress != null) {
                System.out.println("Forwarding email to connected receiver: " + to);

                // Determine the size of data chunks to send (1 KB = 1024 bytes)
                int chunkSize = 1024;

                // Split the data into chunks and send
                int totalLength = emailData.length;
                int numChunks = (totalLength + chunkSize - 1) / chunkSize;  // Number of chunks to send
                DatagramPacket sendPacket;
                PacketObject mailPacket;

                // Sending the data in chunks
                for (int i = 0; i < numChunks; i++) {
                    // Determine start and end indices for each chunk
                    int start = i * chunkSize;
                    int end = Math.min(start + chunkSize, totalLength);

                    // Extract the chunk from the full data
                    byte[] chunk = new byte[end - start];

                    System.arraycopy(emailData, start, chunk, 0, end - start);

                     // Create a new PacketObject for the pacjet
                     mailPacket = new PacketObject(); 
                     mailPacket.SetSequenceNumber((i + 1)); // Assign a unique sequence number for the packet
                     mailPacket.SetType("EMAIL"); // Set the packet type as "EMAIL"
                     mailPacket.SetEndOfMessage(false); // Mark it as not the end of the message yet
                     mailPacket.SetMessage("Mail Chunk"); // Add a descriptive message
                     mailPacket.SetData(chunk); // Attach the packet
                     mailPacket.SetEndOfMessage((i + 1) >= numChunks); // If it's the last chunk, mark it as the end
                 

                     //serialize
                    ByteArrayOutputStream byteArrayOutputStream1 = new ByteArrayOutputStream();
                    ObjectOutputStream objectOutputStream1 = new ObjectOutputStream(byteArrayOutputStream1);
                    objectOutputStream1.writeObject(mailPacket);
                    objectOutputStream1.flush();
                    byte[] packetSendData = byteArrayOutputStream1.toByteArray();
                    sendPacket = new DatagramPacket(packetSendData, packetSendData.length, receiverAddress.getAddress(), receiverAddress.getPort());
                    // Send the packet
                    serverSocket.send(sendPacket);
                    System.out.println("Forwarded Data Packet " + start + " - " + end + " bytes of " + totalLength + " bytes to " + receiverAddress.getAddress() + ":" + receiverAddress.getPort() + " with Packet Sequence: " + (i + 1) + "");

                    // Receive "PKT-ACK response from the server
                    byte[] packetAck = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(packetAck, packetAck.length);
                    serverSocket.receive(receivePacket);

                    PacketObject receivedPacket = GetPacketObject(packetAck);
                    switch (receivedPacket.GetType()) {
                        case "PKT-ACK" -> {
                            System.out.println("Received Data Packet Acknowledgement from " + receiverAddress.getAddress() + ":" + receiverAddress.getPort() + " of Packet Sequence: " + receivedPacket.GetSequenceNumber());
                            continue;
                        }
                    }
                }

                byte[] mailOkAck = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(mailOkAck, mailOkAck.length);
                serverSocket.receive(receivePacket);// Wait for the final "MAIL-OK" acknowledgment

                //decentrlize
                PacketObject mailPacketObject = GetPacketObject(mailOkAck);
                if ("MAIL-OK".equals(mailPacketObject.GetType())) {
                    System.out.println("Received MAIL-OK acknowledgement from client" + to + " with Mail Sequence " + mailPacketObject.GetMessage());
                    MoveToSentFolder(mail);
                    System.out.println("Delivered the email successfully to client" + to + " from "+mail.getSender()+" with Mail Sequence " + mailPacketObject.GetMessage());
                   
                } else {
                    System.out.println("Failed to get MAIL-OK acknowledgement from client" + to);
                }


            // If the receiver is not connected, save the email to the inbox
            } else {
                sendResponse(serverSocket, clientAddress, clientPort, 0, "400", "Receiver is not connected. Email saved to inbox.");
                System.out.println("Receiver is not connected. Email saved to inbox.");

            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    //This method moves an email from the Inbox to the Delivered folder after successful delivery.
    private static void MoveToSentFolder(MailObject mail) {

        // Define the source folder path where the email is currently stored (Inbox)
        Path sourceFolder = Paths.get("Emails/" + mail.getRecipient() + "/InBox/" + mail.GetEmailSequence());
        Path targetDir = Paths.get("Emails/" + mail.getRecipient() + "/Delivered/" + mail.GetEmailSequence());

        // Ensure the target directory exists
        if (!Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir); // Create target directory if it doesn't exist
            } catch (IOException e) {
                System.out.println("Error creating Delivered directory: " + e.getMessage());
                return;
            }
        }

        // Define the target folder path (source folder name remains the same)
        Path targetFolder = targetDir.resolve(sourceFolder.getFileName());

        try {
            // Move the folder (including all its contents)
            Files.move(sourceFolder, targetFolder, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Email with Sequence " + mail.GetEmailSequence() + " moved to Delivered folder.");
        } catch (IOException e) {
            System.out.println("Error moving to Delivered folder: " + e.getMessage());
        }
    }

}
