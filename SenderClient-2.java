//
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SenderClient {

    static int packetSequenceNumber = 1000; //this is created to track te sequence number

    static DatagramSocket clientSocket = null; // Socket used to send and receive data.

    //  expression pattern to validate email addresses.
    static String emailPattern = "^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$";
    static int serverPort = 12345;
    static String from = "";

    public static void main(String[] args) {

        Scanner scanner = new Scanner(System.in);

        try {
            clientSocket = new DatagramSocket();
            System.out.println("\nMail Client Starting on host: " + InetAddress.getLocalHost().getHostName() + "\n");
            InetAddress serverAddress = null; //store server address
            while (serverAddress == null) {// Loop until a valid server address is provided.
                System.out.print("Type the name of Mail server: ");
                String serverName = scanner.nextLine();
                System.out.println();
                try {
                    serverAddress = InetAddress.getByName(serverName); // Resolve the hostname to an IP address.
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

            // Receive SYN-ACK response from the server
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);

            PacketObject receivedPacket = GetPacketObject(receiveData);
            switch (receivedPacket.GetType()) { // Check the type of the received packet
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

            // Get 'FROM' email with validation
            while (true) {
                System.out.print("\nEnter Sender Email: ");
                from = scanner.nextLine();
                if (!Pattern.matches(emailPattern, from)) {
                    // If the email doesn't match the regex format
                    do {
                        System.out.println("501 Error: Invalid email format. Please enter a valid Sender Email.");
                        System.out.print("\nEnter Sender Email: ");
                        from = scanner.nextLine();
                    } while (!Pattern.matches(emailPattern, from)); // Repeated until valid format
                }

                // Send email validation request to server
                sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "FROM-MAIL-VALIDATE", from, true);
                packetSequenceNumber++;
                System.out.println("Validating  Email...");

                // Wait for server's validation response
                receiveData = new byte[1024];
                receivePacket = new DatagramPacket(receiveData, receiveData.length);
                clientSocket.receive(receivePacket);

                PacketObject fromMailValidationObject = GetPacketObject(receiveData);
                if ("550ERROR".equals(fromMailValidationObject.GetType())) {
                    System.out.println("550 Error -The 'Sender mail '" + from + "' not available in the Mail server!. Please enter another..");
                } else {
                    System.out.println("Validated  Email...");
                    break;
                }

            }

            //Checking any undelivered email from the sender, then sending to the receiver one by one
            List<String> outboxFolders = GetOutBoxFolders(from); // Get folder/directory from the outbox

            for (String recieverFolder : outboxFolders) {
                Path parentDir = Paths.get("Client/" + from + "/OutBox/" + recieverFolder); // List all folders in the outbox.
                List<String> outboxMailFolders = getSubfolders(parentDir);
                if (!outboxMailFolders.isEmpty()) {
                    for (String mailfolderName : outboxMailFolders) {
                        System.out.println("Found failed mail to " + recieverFolder + " with Mail Sequence " + mailfolderName);
                        System.out.println("Started re-sending failed mail to " + recieverFolder + " with Mail Sequence " + mailfolderName);
                        // Retrieve the failed email
                        MailObject mail = GetFailedMail(from, recieverFolder, mailfolderName);
                        // Resend the failed email
                        SendEmail(serverAddress, serverPort, clientSocket, mail);
                        System.out.println("Finished re-sending failed mail to " + recieverFolder + " with Mail Sequence " + mailfolderName);
                    }
                }
            }

            // Ask client to choose an option:
           
            while (true) {
                // Giving the option to sync inbox, create email, or terminate connection
                Scanner option = new Scanner(System.in);
                int clientOption; // Initialize to a safe default 
                System.out.println("[1] Create an email");
                System.out.println("[2] Terminate connection");
                System.out.print("Enter an option to proceed: ");

                try {
                    clientOption = option.nextInt(); // Attempt to get a number from the user
                    option.nextLine(); // Consume newline left-over
                } catch (InputMismatchException e) {
                    System.out.println("Invalid input. Please enter a number.");
                    option.nextLine(); // Consume the incorrect input
                    continue; // Prompt the user again at the start of the loop
                }

                switch (clientOption) {

                    case 1 -> { //if user chooses ro swnd new email then call the function CreateAnEmail
                        if (CreateAnEmail(scanner, serverAddress, serverPort, clientSocket)) {
                            return;
                        }
                    }
                    case 2 -> {
                        // Terminate connection 

                        System.out.println("Sending FIN Termination Packet to Server...");
                        sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "FIN", "Terminating connection...", true);
                        packetSequenceNumber++;
                        System.out.println("Waiting for FIN-ACK Packet from Server...");
                        while (true) {
                            // Receive TO-MAIL-VALIDATE response from the server
                            byte[] terminationAckData = new byte[1024];
                            receivePacket = new DatagramPacket(terminationAckData, terminationAckData.length);
                            clientSocket.receive(receivePacket);

                            PacketObject terminationPacketObject = GetPacketObject(terminationAckData);
                            if ("FIN-ACK".equals(terminationPacketObject.GetType())) {
                                System.out.println("FIN-ACK received from server..");
                                System.out.println("Sending ACK-ACK to server..");
                                sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "ACK-ACK", "Terminating connection...", true);

                                clientSocket.close(); //close socket
                                System.out.println("Connection closed and terminating...");
                                return;
                            }
                        }
                    }
                    default ->
                        System.out.println("Invalid option, please try again.");
                }
            }
      
      
        } catch (Exception e) {
            e.printStackTrace();
        } finally {

            if (clientSocket != null && !clientSocket.isClosed()) {
                scanner.close();
                clientSocket.close();
            }
        }
    }


    // Method to send a packet to the server or another client
    private static void sendPacket(DatagramSocket clientSocket, InetAddress clientAddress, int clientPort, int sequenceNo, String type, String message, boolean isEndOfMessage) throws IOException {

        // Create a new PacketObject to hold the packet details
        PacketObject packetObject = new PacketObject();
        packetObject.SetSequenceNumber(sequenceNo); // Set the sequence number of the packet
        packetObject.SetType(type);     //set the type of the email (ex: SYN, ACK...)
        packetObject.SetMessage(message); //comtent of emaol
        packetObject.SetEndOfMessage(isEndOfMessage); // Indicate if this is the last packet in a sequence

        //serialization
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(packetObject);
        objectOutputStream.flush();
        byte[] sendData = byteArrayOutputStream.toByteArray();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);

        // Attempt to send the packet using the provided DatagramSocket
        try {
            clientSocket.send(sendPacket);
        } catch (IOException e) {
            e.printStackTrace();

        }

    }

    //deserialization
    private static PacketObject GetPacketObject(byte[] receivedData) {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(receivedData);
        try (ObjectInputStream objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            PacketObject packetObject = (PacketObject) objectInputStream.readObject();
            return packetObject;
        } catch (ClassNotFoundException | IOException e) {
            System.out.println("\nUnable to Deserialize the incoming packet.. ");
        }
        return null;
    }



    //savign the email to the directory
    private static void SaveEmailToDirectory(MailObject mail) throws FileNotFoundException, IOException {

        File clientDirectory = new File("Client");
        File senderDirectory = new File("Client/" + mail.getSender());
        File outBoxDir = new File("Client/" + mail.getSender() + "/OutBox");
        File receiverOutBoxDir = new File("Client/" + mail.getSender() + "/OutBox/" + mail.getRecipient());
        File identifierDir = new File("Client/" + mail.getSender() + "/OutBox/" + mail.getRecipient() + "/" + mail.GetEmailSequence());
        File emailDir = new File("Client/" + mail.getSender() + "/OutBox/" + mail.getRecipient() + "/" + mail.GetEmailSequence() + "/Mail");
        File attachmentDir = new File("Client/" + mail.getSender() + "/OutBox/" + mail.getRecipient() + "/" + mail.GetEmailSequence() + "/Attachment");

        if (!clientDirectory.exists()) {
            clientDirectory.mkdir(); //create the Client directory if it doesn't exist
        }
        if (!senderDirectory.exists()) {
            senderDirectory.mkdir(); //create the Outbox directory if it doesn't exist
        }
        if (!outBoxDir.exists()) {
            outBoxDir.mkdir(); //create the Outbox directory if it doesn't exist
        }
        if (!receiverOutBoxDir.exists()) {
            receiverOutBoxDir.mkdir(); //create the Outbox directory of recipient inside sender to keep sequenceif it doesn't exist
        }
        if (!identifierDir.exists()) {
            identifierDir.mkdir(); //create the identifier directory if it doesn't exist
        }
        if (!emailDir.exists()) {
            emailDir.mkdir(); // Create the email directory if it doesn't exist
        }
        if (!attachmentDir.exists()) {
            attachmentDir.mkdir(); // Create the attachment directory if it doesn't exist
        }

        String emailContent = "SEQ: " + mail.GetEmailSequence() + "\nTO: " + mail.getRecipient() + "\nFROM: " + mail.getSender() + "\nSubject: " + mail.getSubject() + "\nBody: " + mail.getBody();

        String timeStamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
        File emailFile = new File(emailDir, mail.getSubject() + "_" + timeStamp + ".txt");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(emailFile))) {
            writer.write(emailContent);
            System.out.println("Email content saved at Client Location: " + emailFile.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("Error saving email to file: " + e.getMessage());
        }
        if (mail.getAttachment() != null) {
            File attachmet = new File(attachmentDir, mail.getAttachmentName());
            try (FileOutputStream out = new FileOutputStream(attachmet)) {
                out.write(mail.getAttachment());
                System.out.println("Email Attachment saved at Client Location: " + attachmet.getAbsolutePath());
            } catch (IOException e) {
                System.out.println("Error saving email attachment: " + e.getMessage());
            }
        }
    }

    private static void MoveToSentFolder(MailObject mail) {

        Path sourceFolder = Paths.get("Client/" + mail.getSender() + "/OutBox/" + mail.getRecipient() + "/" + mail.GetEmailSequence());
        Path targetDir = Paths.get("Client/" + mail.getSender() + "/Sent/" + mail.getRecipient());

        // Ensure the target directory exists
        if (!Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir); // Create target directory if it doesn't exist
            } catch (IOException e) {
                System.out.println("Error creating Sent directory: " + e.getMessage());
                return;
            }
        }

        // Define the target folder path (source folder name remains the same)
        Path targetFolder = targetDir.resolve(sourceFolder.getFileName());

        try {
            // Move the folder (including all its contents)
            Files.move(sourceFolder, targetFolder, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Email moved to Sent folder.");
        } catch (IOException e) {
            System.out.println("Error moving to Sentfolder: " + e.getMessage());
        }
    }


    //retrieve a list of directories in the outbox
    private static List<String> GetOutBoxFolders(String senderMail) {
        List<String> subFolders = new ArrayList<>(); //empty list
        Path parentDir = Paths.get("Client/" + senderMail + "/OutBox/"); // Define the path to the sender's OutBox directory
        try {
            //get subfolder names from the main directory
            subFolders = getSubfolders(parentDir);

        } catch (IOException e) { 
        }
        return subFolders;
    }

    public static List<String> getSubfolders(Path parentDir) throws IOException {
        List<String> subfolders = new ArrayList<>();

        // List all the entries in the parent directory
        try (Stream<Path> paths = Files.list(parentDir)) {
            // Filter to only directories
            paths.filter(Files::isDirectory)
                    .forEach(dir -> subfolders.add(dir.getFileName().toString())); // Add folder name to the list
        }

        return subfolders;
    }

    private static boolean CreateAnEmail(Scanner scanner, InetAddress serverAddress, int serverPort, DatagramSocket clientSocket) throws IOException {
        DatagramPacket receivePacket;
        // Step 2: Email and attachment sending logic
        boolean keepSending = true;
        while (keepSending) {
            System.out.println("\nCreating New Email...");
            // Define a pattern for email validation
            // Validate the email format using regex for both TO and FROM

            String to, subject, body;
            // Get 'TO' email with validation

            while (true) {
                System.out.print("TO: ");
                to = scanner.nextLine();
                if (!Pattern.matches(emailPattern, to)) {
                    // If the email doesn't match the regex format
                    do {
                        System.out.println("501 Error: Invalid email format. Please enter a valid TO email.");
                        System.out.print("\nTO: ");
                        to = scanner.nextLine();
                    } while (!Pattern.matches(emailPattern, to)); // Repeated until valid format

                }

                sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "TO-MAIL-VALIDATE", to, true);
                System.out.println("Validating  Email...");
                packetSequenceNumber++;
                // Receive TO-MAIL-VALIDATE response from the server
                byte[] receiveData = new byte[1024];
                receivePacket = new DatagramPacket(receiveData, receiveData.length);
                clientSocket.receive(receivePacket);

                PacketObject toMailValidationObject = GetPacketObject(receiveData);
                if ("550ERROR".equals(toMailValidationObject.GetType())) {
                    System.out.println("550 Error - The 'To' mail '" + to + "' is not available in the Mail server!. Please enter another..");
                } else {
                    System.out.println("Validated  Email...");
                    break;
                }
            }

            // Get 'Subject' and 'Body'
            System.out.print("\nSubject: ");
            subject = scanner.nextLine();
            System.out.print("\nBody: ");
            body = scanner.nextLine();
            // Ask the user if they want to add an attachment
            System.out.print("\nWould you like to add an attachment? (yes/no): ");
            String addAttachment = scanner.nextLine().trim().toLowerCase();
            // Prepare to hold the attachment data if any
            byte[] attachmentData = null;
            String attachmentName = "";
            boolean validAttachment = false; // Flag to validate the attachment path
            if (addAttachment.equals("yes")) {

                while (!validAttachment) {
                    System.out.print("Enter the file path of the attachment: ");
                    String filePath = scanner.nextLine();

                    File file = new File(filePath);
                    if (!file.exists() || file.isDirectory()) {
                        System.out.println("Invalid file path. Please try again.");
                    } else {
                        // Read the file into a byte array
                        attachmentName = file.getName();
                        attachmentData = new byte[(int) file.length()];
                        try (FileInputStream fis = new FileInputStream(file)) {
                            fis.read(attachmentData);
                        }
                        validAttachment = true; // Mark the attachment as valid
                        System.out.println("Attachment added successfully.");
                    }
                }
            }

            int sequence = 0;
            sendPacket(clientSocket, serverAddress, serverPort, packetSequenceNumber, "GET-SEQ", to, true);
            packetSequenceNumber++;
            System.out.println("Fetching  Email Sequence Number..");
            // Receive FROM-MAIL-VALIDATE response from the server
            byte[] receiveData = new byte[1024];
            receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);

            PacketObject sequencePacketObject = GetPacketObject(receiveData);
            System.out.println("Received Email Sequence Number..");
            sequence = Integer.parseInt(sequencePacketObject.GetMessage());

            MailObject mail = new MailObject(sequence, from, to, subject, body, attachmentData, attachmentName);

            // Save the email content to a file before sending 
            SaveEmailToDirectory(mail);

            SendEmail(serverAddress, serverPort, clientSocket, mail);

            // Ask the user if they want to send another email
            System.out.print("\nWould you like to send another email? (yes/no): ");
            String response = scanner.nextLine().trim().toLowerCase();
            if (!response.equals("yes")) {
                keepSending = false;
            }
        }
        return false;

    }

    private static void SendEmail(InetAddress serverAddress, int serverPort, DatagramSocket clientSocket, MailObject mail) throws IOException {
        byte[] sendData;
        DatagramPacket sendPacket;
        DatagramPacket receivePacket;
        // Serialize the MailObject to a byte array
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        ObjectOutputStream objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
        objectOutputStream.writeObject(mail);
        objectOutputStream.flush();
        sendData = byteArrayOutputStream.toByteArray();
        // Determine the size of data chunks to send (1 KB = 1024 bytes)
        int chunkSize = 1024;
        // Split the data into chunks and send
        int totalLength = sendData.length;
        int numChunks = (totalLength + chunkSize - 1) / chunkSize;  // Number of chunks to send
        System.out.println("Started Sending Email with Email Sequence Number: " + mail.GetEmailSequence());

        PacketObject mailPacket;

        // Sending the data in chunks
        for (int i = 0; i < numChunks; i++) {
            // Determine start and end indices for each chunk
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, totalLength);

            // Extract the chunk from the full data
            byte[] chunk = new byte[end - start];

            System.arraycopy(sendData, start, chunk, 0, end - start);

            mailPacket = new PacketObject();
            mailPacket.SetSequenceNumber((i + 1));
            mailPacket.SetType("EMAIL");
            mailPacket.SetEndOfMessage(false);
            mailPacket.SetMessage("Mail Chunk");
            mailPacket.SetData(chunk);
            mailPacket.SetEndOfMessage((i + 1) >= numChunks);
            // Create a DatagramPacket for this chunk
            ByteArrayOutputStream byteArrayOutputStream1 = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream1 = new ObjectOutputStream(byteArrayOutputStream1);
            objectOutputStream1.writeObject(mailPacket);
            objectOutputStream1.flush();
            byte[] packetSendData = byteArrayOutputStream1.toByteArray();
            sendPacket = new DatagramPacket(packetSendData, packetSendData.length, serverAddress, serverPort);
            // Send the packet
            clientSocket.send(sendPacket);

            System.out.println("Sent Data Packet " + start + " - " + end + " bytes of " + totalLength + " bytes with Packet Sequence: " + (i + 1) + "");
            System.out.println("Waiting for PKT-ACK from server for Data Packet " + start + " - " + end + " bytes of " + totalLength + " bytes with Packet Sequence: " + (i + 1) + "");
            // Receive "PKT-ACK response from the server
            byte[] packetAck = new byte[1024];
            receivePacket = new DatagramPacket(packetAck, packetAck.length);
            clientSocket.receive(receivePacket);

            PacketObject receivedPacket = GetPacketObject(packetAck);
            switch (receivedPacket.GetType()) {
                case "PKT-ACK" -> {
                    System.out.println("Received  PKT-ACK from server for Data Packet " + start + " - " + end + " bytes of " + totalLength + " bytes with Packet Sequence: " + (i + 1) + "");
                    continue;
                }
            }

        }
        boolean waiting = true;
        System.out.println("Completed Sending Email with Email Sequence Number: " + mail.GetEmailSequence());
        MoveToSentFolder(mail);
        System.out.println("Waiting for the Email processing status from the server for the Mail with Email Sequence Number: " + mail.GetEmailSequence());

        while (waiting) { //  wait for responses from the server

            byte[] packetAck = new byte[1024];
            receivePacket = new DatagramPacket(packetAck, packetAck.length);
            clientSocket.receive(receivePacket); // Wait to receive a response from the server

            PacketObject receivedPacket = GetPacketObject(packetAck); //decerialize

            // Process the type of the received packet and send approperiate message
            switch (receivedPacket.GetType()) {

                case "250OK1" -> {
                    System.out.println("250 OK - Email Received Successfully on Server from " + mail.getSender() + "");
                    continue;
                }
                case "250OK2" -> {
                    System.out.println("250 OK - The header fields are verified on Server from " + mail.getSender() + ".\nThe email sent successfully!");

                    continue;
                }
                case "550ERROR" -> {
                    System.out.println("550 Error - The header fields are not valid. Email rejected.");

                    continue;
                }
                case "400" -> {
                    System.out.println("Receiver is not connected. Email saved to inbox.");
                    continue;
                }
                case "MAIL-COMP" -> {
                    waiting = false;
                    continue;
                }
                default -> {
                    System.out.println("Not Received Data Packet Acknowledgement" + receivedPacket.GetType());
                }
            }
        }
    }


    // Retrieve a failed email from the client's outbox directory
    private static MailObject GetFailedMail(String senderMail, String receiverMail, String sequenceNumber) {

        MailObject mail;
        String mailFileName = "";
        String attachmentFilename = "";
        byte[] attachment = null;

         // Locate the folder containing the email in the outbox
        File emailFolder = new File("Client/" + senderMail + "/OutBox/" + receiverMail + "/" + sequenceNumber + "/Mail");
        File[] mailFiles = emailFolder.listFiles(); // Get the list of files in the folder

        // Loop through files to identify the email file
        if (mailFiles != null) {
            for (File file : mailFiles) {
                // Check if it's a file (not a directory)
                if (file.isFile()) {
                    mailFileName = file.getName();
                }
            }
        }
        String From = "";
        String To = "";
        String Subject = "";
        String Body = "";
        int Sequence = 0;

        if (!"".equals(mailFileName)) { // If an email file is found, read its content
            Path path = Path.of("Client/" + senderMail + "/OutBox/" + receiverMail + "/" + sequenceNumber + "/Mail/" + mailFileName);
            try {
                String content = Files.readString(path);
                String[] mailItems = content.split("\n");
                Sequence = Integer.parseInt(mailItems[0].replace("SEQ: ", "").trim());
                To = mailItems[1].replace("TO: ", "");
                From = mailItems[2].replace("FROM: ", "");
                Subject = mailItems[3].replace("Subject: ", "");
                Body = mailItems[4].replace("Body: ", "");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Define the directory containing the email's attachments
        File AttachmentFolder = new File("Client/" + senderMail + "/OutBox/" + receiverMail + "/" + sequenceNumber + "/Attachment");
        File[] attachmentFiles = AttachmentFolder.listFiles();// List all files in the attachment directory

        if (attachmentFiles != null) { //if file isnt empty then proccess it
            for (File file : attachmentFiles) {
                if (file.isFile()) {
                    attachmentFilename = file.getName();
                }
            }
        }
        
        if (!"".equals(attachmentFilename)) { // If an attachment file was found
            // Define the path to the attachment file
            File file = new File("Client/" + senderMail + "/OutBox/" + receiverMail + "/" + sequenceNumber + "/Attachment/" + attachmentFilename);

            // Validate the file's existence and ensure it is not a directory
            if (!file.exists() || file.isDirectory()) {
                System.out.println("Invalid file path.!.");
            } else {
                attachment = new byte[(int) file.length()];
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

}
