
import java.io.Serializable;

public class PacketObject implements Serializable {

    int sequenceNumber;
    String type;
    String message;
    byte[] data;
    boolean endOfMessage;

     // Constructor to initialize the packet with a default sequence number of 0
    public PacketObject() {
        sequenceNumber = 0;
    }

    public void SetSequenceNumber(int value) { // Setter method to assign a value to the sequence number
        this.sequenceNumber = value;
    }

    public int GetSequenceNumber() { // Getter method to retrieve the sequence number
        return this.sequenceNumber;
    }


    // Setter method to define the type of the packet
    public void SetType(String value) {
        this.type = value;
    }


    // Getter method to retrieve the packet type
    public String GetType() {
        return this.type;
    }

    // Setter method to define the message in the packet
    public void SetMessage(String value) {
        this.message = value;
    }

    // Getter method to retrieve the message in the packet
    public String GetMessage() {
        return this.message;
    }

    // Setter method to mark whether this is the final packet in a sequence
    public void SetEndOfMessage(boolean value) {
        this.endOfMessage = value;
    }

    // Getter method to check if this is the final packet in a sequence
    public boolean GetEndOfMessage() {
        return this.endOfMessage;
    }

    // Setter method to assign data to the packet
    public void SetData(byte[] value) {
        this.data = value;
    }

    // Getter method to retrieve the packet's data payload
    public byte[] GetData() {
        return this.data;
    }

}
