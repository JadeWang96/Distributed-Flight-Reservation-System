import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;

public class Acceptor extends Thread {
    private TreeMap<Integer, Integer> maxPrepare;//log_slot to maxPrepare
    private TreeMap<Integer, Integer> accNum;
    private TreeMap<Integer, String> accVal;
    public static TreeMap<Integer, String> proposerIp;
    private DatagramSocket receiveSocket;
    private DatagramSocket sendSocket;
    private boolean running;
    private byte[] buffer = new byte[65535];
    private ArrayList<HashMap<String, String>> sitesInfo;
    private BlockingQueue<String> proposerQueue = null;
    private BlockingQueue<String> learnerQueue = null;
//    public static TreeMap<String, String> findIP;



    public Acceptor(BlockingQueue<String> proposerQueue, BlockingQueue<String> learnerQueue, DatagramSocket receiveSocket, DatagramSocket sendSocket,
                    ArrayList<HashMap<String, String>> sitesInfo) throws IOException, ClassNotFoundException {
        this.maxPrepare = new TreeMap<>();
        this.accNum = new TreeMap<>();
        this.accVal = new TreeMap<>();
        proposerIp = new TreeMap<>();
//        findIP = new TreeMap<>();
        File acceptorFile = new File(Host.curSiteId +"acceptor.txt");
        if (acceptorFile.exists()) {
            recoverAcceptor();
        }
        this.receiveSocket = receiveSocket;
        this.sendSocket = sendSocket;
        this.running = true;
        this.sitesInfo = sitesInfo;
        this.proposerQueue = proposerQueue;
        this.learnerQueue = learnerQueue;
    }

    public void run() {
        DatagramPacket packet = null;

        while (this.running) {
            // Receive from other server
            packet = new DatagramPacket(this.buffer, this.buffer.length);
            try {
                receiveSocket.receive(packet);// blocks until a msg arrives
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Processing information
            String senderIp = packet.getAddress().getHostAddress();
            String senderId = null;
            for (int i = 0; i < this.sitesInfo.size(); i++) {
                if (this.sitesInfo.get(i).get("ip").equals(senderIp) &&
                        !this.sitesInfo.get(i).get("siteId").equals(Host.curSiteId)) {
                    senderId = this.sitesInfo.get(i).get("siteId");
//                    System.out.println("[test] Got something from site " + senderId);
                    break;
                }
            }

            // handle the receiving messages
            String recvMessage = null;
            try {
                recvMessage = (String) deserialize(packet.getData());

            } catch (IOException | ClassNotFoundException e) {
                e.printStackTrace();
            }
            // parse the string
            assert recvMessage != null;
            //System.out.println("+++[test] " + Host.curSiteId + " receives =>" + recvMessage);
            String[] getCommand = recvMessage.split(" ");//prepare
            // FIXME
            if (getCommand[0].equals("lastSeen")) {
//                System.out.println("####received a last Seen!!");
                // update last seen
                for (int i = 1; i < getCommand.length; i++) {
                    String[] oneTwo = getCommand[i].split(",");
                    Host.lastSeen.put(oneTwo[0], oneTwo[1]);
                }
                // keep in stable storage
                try {
                    Host.storeLastSeen();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (getCommand[0].equals("promise") || getCommand[0].equals("ack")
                    || getCommand[0].equals("nack")) {// A->P
                System.err.println("Proposer<" + Host.curSiteId + "> received " + recvMessage + " from " + ipToID(senderIp));
                //System.out.println("[test]A->P transmission through blocking queue is: " + transmission);

                try {
                    this.proposerQueue.put(recvMessage);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } else if (getCommand[0].equals("prepare") || getCommand[0].equals("accept")) {// P->A
                if (getCommand[0].equals("prepare")) {
                    try {
                        System.err.println("Acceptor<" + Host.curSiteId + "> received prepare(" + getCommand[1] + ") from " + ipToID(senderIp));
                        recvPrepare(Integer.parseInt(getCommand[1]), senderIp, Integer.parseInt(getCommand[2]));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        System.err.println("Acceptor<" + Host.curSiteId + "> received "+ recvMessage
                                +" from " + ipToID(senderIp));
//                        if (!findIP.containsKey(getCommand[3])) {
//                            findIP.put(getCommand[3], senderIp);
//                        }
                        if (getCommand[2].equals("cancel")) {
                            recvAccept(Integer.parseInt(getCommand[1]), getCommand[2] + " " + getCommand[3],
                                    senderIp, Integer.parseInt(getCommand[4]));
                        } else {
                            String accVal = "";
                            for (int i = 2; i < getCommand.length - 1; i++) {
                                accVal += getCommand[i];
                                accVal += " ";
                            }
                            recvAccept(Integer.parseInt(getCommand[1]), accVal.trim(), senderIp, Integer.parseInt(getCommand[getCommand.length - 1]));
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

            } else if (getCommand[0].equals("accepted") || getCommand[0].equals("commit")) {// A->DL & DL->L
                System.err.println("Learner<" + Host.curSiteId + "> received " + recvMessage + " from " + ipToID(senderIp));
                //System.out.println("[test] A To L transmission through block queue is: " + transmission);

                try {
                    this.learnerQueue.put(recvMessage);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } else if (getCommand[0].equals("MaximumLog")) {
                try {
                    acceptorSend(senderIp, "Max " + Integer.toString(Learner.getMaxLogSlot()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //System.out.println("[test]Oops, wrong msg!");
            } else if (getCommand[0].equals("Max")) {
                //System.out.println("%%%%#####Recive ask max " + getCommand[1]);
                Learner.newMax = Integer.parseInt(getCommand[1]) > Learner.newMax ? Integer.parseInt(getCommand[1]) : Learner.newMax;

                try {
                    learnerQueue.put(recvMessage);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            this.buffer = new byte[65535];//reset
        }
        receiveSocket.close();
    }

    // Deserialize the byte array and reconstruct the object
    public static Object deserialize(byte[] buffer) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(buffer);
        ObjectInputStream objStream = new ObjectInputStream(byteStream);
        return objStream.readObject();
    }

    // @From: Proposer
    // @To: Acceptor(current)
    public void recvPrepare(Integer n, String senderIP, Integer logSlot) throws IOException {
        if (!this.maxPrepare.containsKey(logSlot) || n > this.maxPrepare.get(logSlot)) {
            this.maxPrepare.put(logSlot, n);
            recordAcceptor();
            sendPromise(senderIP, logSlot);
        } else {
            sendNack(senderIP, logSlot);
        }
        Host.sendLastSeen(sendSocket);
    }

    // @From: Proposer
    // @To: Acceptor(current)
    // ToDo: check null pointer
    public void recvAccept(Integer n, String v, String senderIP, Integer logSlot) throws IOException {
        if (!this.maxPrepare.containsKey(logSlot)) this.maxPrepare.put(logSlot, 0);
        if (this.maxPrepare.containsKey(logSlot) && n >= this.maxPrepare.get(logSlot)) {
            this.accNum.put(logSlot, n);
            this.accVal.put(logSlot, v);
            this.maxPrepare.put(logSlot, n);
            recordAcceptor();
            sendAck(senderIP, logSlot);
            sendAccepted(senderIP, logSlot);
        } else {
            sendNack(senderIP, logSlot);
        }
        Host.sendLastSeen(sendSocket);
    }

    // @From: Acceptor(current)
    // @To: Proposer
    public void sendPromise(String senderIP, Integer logSlot) throws IOException {
        String promiseMsg = null;
        if (this.accNum.containsKey(logSlot) && this.accVal.containsKey(logSlot)) {
            promiseMsg = "promise " + Integer.toString(this.accNum.get(logSlot)) + " "
                    + this.accVal.get(logSlot) + " " + Integer.toString(logSlot) + " " + Host.curIp;
        } else {
            String curAccNum = "null", curAccVal = "null";
            if (this.accNum.containsKey(logSlot)) {
                curAccNum = Integer.toString(this.accNum.get(logSlot));
            }
            if (this.accVal.containsKey(logSlot)) {
                curAccVal = this.accVal.get(logSlot);
            }
            promiseMsg = "promise " + curAccNum + " " + curAccVal + " " + Integer.toString(logSlot) + " " + Host.curIp;
        }
        acceptorSend(senderIP, promiseMsg);
        System.err.println("Acceptor<" + Host.curSiteId + "> sends " + promiseMsg +" to " + ipToID(senderIP));
        Host.sendLastSeen(sendSocket);
    }

    // @From: Acceptor(current)
    // @To: Proposer
    public void sendNack(String senderIP, Integer logSlot) throws IOException {
        String maxNum = "0";
        if (this.maxPrepare.containsKey(logSlot)) {
            maxNum = Integer.toString(this.maxPrepare.get(logSlot));
        }
        String nackMsg = "nack " + maxNum + " " + Host.curIp;
        acceptorSend(senderIP, nackMsg);
        System.err.println("Acceptor<" + Host.curSiteId + "> sends nack(" +
                maxNum  +") to " + ipToID(senderIP));
        Host.sendLastSeen(sendSocket);
    }

    // @From: Acceptor(current)
    // @To: Proposer
    public void sendAck(String senderIP, Integer logSlot) throws IOException {
        if (!proposerIp.containsKey(logSlot)) {
            proposerIp.put(logSlot, senderIP);
        }
        String ackMsg = "ack " + Integer.toString(this.maxPrepare.get(logSlot)) + " " + proposerIp.get(logSlot);
        acceptorSend(senderIP, ackMsg);
        System.err.println("Acceptor<" + Host.curSiteId + "> sends ack(" +
                Integer.toString(this.maxPrepare.get(logSlot))  +") to " + ipToID(senderIP));
        Host.sendLastSeen(sendSocket);
    }

    // @From: Acceptor(current)
    // @To: Distinguished Learner
    // The proposer that proposed this proposal is the DL
    public void sendAccepted(String senderIP, Integer logSlot) throws IOException {
        String acceptedMsg = "accepted " + Integer.toString(this.accNum.get(logSlot)) + " "
                + this.accVal.get(logSlot) + " " + Integer.toString(logSlot) + " " + Host.curIp;
        acceptorSend(senderIP, acceptedMsg);
        System.err.println("Acceptor<" + Host.curSiteId + "> sends accepted(" +
                Integer.toString(this.accNum.get(logSlot)) + ","
                + this.accVal.get(logSlot)  +") to " + ipToID(senderIP));
        Host.sendLastSeen(sendSocket);
    }

    public void acceptorSend(String senderIP, String message) throws IOException {
        InetAddress targetIP = InetAddress.getByName(senderIP);
        byte[] sendArray = Send.serialize(message);
        String receivePort = null;
        for (int j = 0; j < this.sitesInfo.size(); j++) {
            if (this.sitesInfo.get(j).get("ip").equals(senderIP)) {
                receivePort = this.sitesInfo.get(j).get("startPort");
                break;
            }
        }
        assert receivePort != null;
        DatagramPacket sendPacket = new DatagramPacket(sendArray, sendArray.length, targetIP, Integer.parseInt(receivePort));
        this.sendSocket.send(sendPacket);
        Host.sendLastSeen(sendSocket);
    }

    private void recordAcceptor() throws IOException {
        Record log = new Record(this.maxPrepare, this.accNum, this.accVal, proposerIp);
        byte[] output = Send.serialize(log);
        File file = new File(Host.curSiteId +"acceptor.txt");
        FileOutputStream fos = null;
        fos = new FileOutputStream(file);
        fos.write(output);
        fos.close();
    }

    private void recoverAcceptor() throws IOException, ClassNotFoundException {
        Record recover = (Record)deserialize(readFromFile(Host.curSiteId +"acceptor.txt"));
        this.maxPrepare = recover.getMaxPrepare();
        this.accNum = recover.getAccNum();
        this.accVal = recover.getAccVal();
        proposerIp = recover.getProposerIp();
    }

    public static byte[] readFromFile(String fileName) throws IOException {
        File file = new File(fileName);
        byte[] getBytes = {};
        getBytes = new byte[(int) file.length()];
        InputStream is = new FileInputStream(file);
        is.read(getBytes);
        is.close();
        return getBytes;
    }

    public String ipToID(String ip) {
        for (int j = 0; j < this.sitesInfo.size(); j++) {
            if (this.sitesInfo.get(j).get("ip").equals(ip)) {
                return this.sitesInfo.get(j).get("siteId");
            }
        }
        return null;
    }
}



