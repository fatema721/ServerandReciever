
import java.io.*;

// This class represents an email object and implements Serializable for object transmission.
public class MailObject implements Serializable {

    private int emailSequence;
    private String sender; //sender's email address
    private String recipient; //receiver/receipient email address
    private String subject;
    private String body;
    private byte[] attachment;  // Attachment stored as a byte array
    private String attachmentName;

    // Constructor for the MailObject
    public MailObject(int emailSequence, String sender, String recipient, String subject, String body, byte[] attachment,String attachmentName) {
        this.emailSequence = emailSequence;
        this.sender = sender;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.attachment = attachment;
        this.attachmentName=attachmentName;
    }

    // Getters emails info

    public int GetEmailSequence() {
        return emailSequence;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public byte[] getAttachment() {
        return attachment;
    }
    public String getAttachmentName() {
        return attachmentName;
    }

    // Override the toString() method to display email details in a readable format
    @Override
    public String toString() {
        return "MailObject{"
                + "sender='" + sender + '\''
                + ", recipient='" + recipient + '\''
                + ", subject='" + subject + '\''
                + ", body='" + body + '\''
                + ", attachmentSize=" + (attachment != null ? attachment.length : 0) + " bytes"
                + '}';
    }
}
