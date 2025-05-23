import java.io.*;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.net.*;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;

public class WordleClientMain {
    //Support class relative to a player statistics
    static class Stat{
            int     playedWordles=0,    //Number of TOTAL played wordles

                    currentStreak=0,    //Properties relative to "streaks" (streak intended as number of consecutive wins)
                    lastStreak=0,
                    maxStreak=0;

            double  winRatio=0;         //Ratio of ho won games

            ArrayList<Integer> guessDistribution;   //Distribution of correct guesses across all the possible tries
        Stat(int max_tries){
            this.guessDistribution = new ArrayList<Integer>(max_tries);
            for(int i = 0; i<max_tries; i++)guessDistribution.add(i,0);
        }
    }

    //Support method to parse "ClientSettings.json" and load settings
    public static boolean loadSettings(){
        try{
            JsonReader supp = new JsonReader(new FileReader("ClientSettings.json"));
            supp.beginObject();

            //region SERVER_IP
            //Parsing "server_ip" field
            if(!supp.nextName().contentEquals("server_ip")){
                System.out.println("[CLIENT] ERROR server_ip field is absent");
                return false;
            }
            SERVER_IP = supp.nextString();

            if(SERVER_IP.contentEquals("0")){
                SERVER_IP = InetAddress.getLocalHost().getHostAddress();
            }else{
                String[] suppStr;
                if(SERVER_IP ==null || SERVER_IP.isEmpty() || (suppStr= SERVER_IP.split("\\.")).length!=4 || SERVER_IP.endsWith(".")){
                    System.out.println("[CLIENT] ERROR Value of field \"server_ip\" is not a parsable string that represents an IP");
                    return false;
                }
                int a;
                for(String part : suppStr){
                    a = Integer.parseInt(part);
                    if(a<0 || a>255){
                        System.out.println("[CLIENT] ERROR Value of field \"server_ip\" is not a parsable string that represents an IP");
                        return false;
                    }
                }
            }
            //endregion

            //region SERVER_PORT
            //Parsing "server_port" field
            if((!supp.nextName().contentEquals("server_port"))){
                System.out.println("[CLIENT] ERROR server_port field is absent");
                return false;
            }
            try{
                SERVER_PORT = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[CLIENT] ERROR Value of field \"server_port\" is not a parsable string that represents a PORT");
                return false;
            }
            if(SERVER_PORT <=0) SERVER_PORT = 10000;
            //endregion

            //region MULTICAST_IP
            //Parsing "multicast_ip" field
            if(!supp.nextName().contentEquals("multicast_ip")){
                System.out.println("[CLIENT] ERROR multicast_ip field is absent");
                return false;
            }
            MULTICAST_IP = supp.nextString();

            if(MULTICAST_IP.contentEquals("0")){
                MULTICAST_IP = "224.0.0.0";
            }else{
                String[] suppStr;
                if(MULTICAST_IP ==null || MULTICAST_IP.isEmpty() || (suppStr= MULTICAST_IP.split("\\.")).length!=4 || MULTICAST_IP.endsWith(".")){
                    System.out.println("[CLIENT] ERROR value of field \"multicast_ip\" is not a parsable string that represents an IP");
                    return false;
                }
                int a;
                for(String part : suppStr){
                    a = Integer.parseInt(part);
                    if(a<0 || a>255){
                        System.out.println("[CLIENT] ERROR Value of field \"multicast_ip\" is not a parsable string that represents an IP");
                        return false;
                    }
                }
            }
            //endregion

            //region MULTICAST PORT
            //Parsing "multicast_post" field
            if((!supp.nextName().contentEquals("multicast_port"))){
                System.out.println("[CLIENT] ERROR multicast_port field is absent");
                return false;
            }
            try{
                MULTICAST_PORT = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[CLIENT] ERROR value of field \"multicast_port\" is not a parsable string that represents a PORT");
                return false;
            }
            if(MULTICAST_PORT <=0) MULTICAST_PORT = 10000;
            //endregion

            supp.endObject();
            supp.close();
            return true;
        }catch(FileNotFoundException e){
            System.out.println("[CLIENT] ERROR ClientSettings.json absent");
            return false;
        }catch(Exception e){
            e.printStackTrace();
            return false;
        }
    }

    //Support method to establish a connection when needed (e.g. when logging or signing up)
    public static boolean connect(String ip,int port){
        try{
            conn = new Socket(ip, port);
            in = new Scanner(conn.getInputStream());
            out = new PrintWriter(conn.getOutputStream(), true);
            connessione = true;
            return true;
        }catch (UnknownHostException e){
            System.out.println("[CLIENT] ERROR Cannot find the server specified in the settings file");
            return false;
        }catch (ConnectException e){
            System.out.println("[CLIENT] ERROR Cannot connect to server!");
            return false;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }

    //Support method to disconnect
    public static void disconnect(){
        conn = null; in = null; out = null; connessione = false;
    }

    //Support method: first step to do for any command
    public static void user_server_response(){
        out.println(user);
        serverResponseCode = Integer.parseInt(in.nextLine());
    }

    //support method
    public static void username_password(int option) throws IllegalStateException{
        if(option==0){System.out.println("Please enter a username");}
        else{System.out.println("Please enter a password");}
        System.out.print(">");
        user = console.nextLine();
        user_server_response();
    }

    //CLIENT PARAMETERS
    static String SERVER_IP;
    static int SERVER_PORT;

    volatile static String MULTICAST_IP;
    volatile static int MULTICAST_PORT;

    //SOCKET
    static Socket conn;
    static Scanner in=null,console=null;
    static PrintWriter out=null;

    //SUPPORT PROPERTIES
    static final Gson gson = new Gson();
    static final Type statType = new TypeToken<Stat>() {}.getType();

    //SESSION PROPERTIES
    static String user;
    static int serverResponseCode;
    static boolean connessione = false;

    //MULTICAST LISTENER: properties and methods------------------------------------------------------------------------
    static Thread multicastListener;

    //Shared results on the multicast socket, it can be accessed from the two methods associated with it
    final static LinkedBlockingQueue<String> shared_results = new LinkedBlockingQueue<>();

    public static void show_shared_results(){
        Iterator<String> iter = shared_results.iterator();
        while(iter.hasNext()){
            System.out.println(iter.next());
        }

    }

    //Task that listens constantly to multicastsocket to save notifications
    //As described in client specifications the multicast is ONLY JOINED when login is performed
    //(from that moment on the client will ALWAYS receive notifications)
    public static class MulticastListener implements Runnable{
        @Override
        public void run() {
            try(MulticastSocket socket =new MulticastSocket(MULTICAST_PORT)){
                InetAddress group = InetAddress.getByName(MULTICAST_IP);
                socket.joinGroup(group);
                while(true){
                    try{
                        byte[] buffer = new byte[1024];
                        DatagramPacket receivePacket = new DatagramPacket(buffer,buffer.length);
                        socket.receive(receivePacket);
                        String[] result = new String(receivePacket.getData(),0,receivePacket.getLength()).split("\n");
                        int supp;
                        String resultString ="-------------------------------\n"+result[0]+"\n\n";
                        String suppString1, suppString2="";
                        for(int j = 1; j<result.length; j++) {
                            suppString1= j +"\t";
                            char[] line = result[j].toCharArray();
                            for (char c : line) {
                                supp = Character.getNumericValue(c);
                                if (supp == 0) {
                                    suppString1 = suppString1.concat("|" + "\u001b[7m" + "\u001b[32m" + " " + "\u001b[0m");
                                } else if (supp == 1) {
                                    suppString1 = suppString1.concat("|" + "\u001b[7m" + "\u001b[33m" + " " + "\u001b[0m");
                                } else {
                                    suppString1 = suppString1.concat("|" + "\u001b[7m" + " " + "\u001b[0m");
                                }
                            }
                            String suppString3 = "";
                            for(int k = 0; k<(result[1].length()*2)+4; k++)suppString3 = suppString3.concat("-");
                            suppString2 = suppString2.concat(suppString1+"\n"+suppString3+"\n");
                        }
                        shared_results.add(resultString.concat(suppString2));

                    }catch(Exception e){
                        System.out.println("\u001B[35m[CLIENT]\u001B[31m ERROR An error occured while reading a shared result\u001B[0m");
                        break;
                    }
                }
            }catch(Exception e){
                System.out.println("\u001B[35m[CLIENT]\u001B[31m ERROR An error occured in the thread listening for shared results\u001B[0m");
                e.printStackTrace();
            }


        }
    }



    //MAIN--------------------------------------------------------------------------------------------------------------

    public static void main(String[] args) {
        try{
            System.out.println("-----------WELCOME TO WORDLE!-----------");
            //CLIENT INIT
            if(!loadSettings())return;
            console = new Scanner(System.in);

            //COMMUNICATION PROTOCOL
            //The protocol consists of two phases:
                /*
                    1. USER_SERVER_RESPONSE: The client sends one of the possible commands.
                    The server then proceeds to send back a code regarding that status of the requested command, based
                    on possible scenarios (accepted, not accepted for various reasons)
                    (This phase is represented in the code with 2 tags: USR-BEGIN and USR-END)
                */
                /*
                    2. COMMAND EXECUTION: The received command is executed. Based on which command has been chosen
                    the server might continue a "server-client" type communication as some commands require more info
                    to be executed
                    (e.g. logging in requires both username and password, playing requires more steps, etc...)
                */

            while(true){
                try{
                    System.out.println();
                    System.out.println("Type in one of the following commands:\nlogin\nregister\nplay\nstatistics\nshowMeSharing\nlogout");
                    System.out.print(">");

                    //USR-BEGIN
                    user = console.nextLine();
                    System.out.println();

                    //LOGIN
                    if(user.contentEquals("login")) {
                        if(!connessione){
                            if(!connect(SERVER_IP, SERVER_PORT))continue;
                        }

                        user_server_response();
                        if(serverResponseCode ==1){
                            System.out.println("[SERVER] You are already logged in!");
                            continue;
                        }
                        //USR-END

                        //USERNAME PHASE
                        while(true){
                            username_password(0);
                            if(serverResponseCode ==1){
                                System.out.println("[SERVER] Username not existing! :/");
                            }else if(serverResponseCode !=0 && serverResponseCode !=2){
                                System.out.println("[SERVER] Communication error :C please re-enter your username.");
                            }else{break;}
                        }
                        if(serverResponseCode ==2){
                            System.out.println("[SERVER] Account already logged in! ಠ_ಠ");
                            continue;
                        }

                        //PASSWORD PHASE
                        while (true) {
                            username_password(1);
                            if(serverResponseCode ==1){
                                System.out.println("[SERVER] Wrong password :/");
                            }else if(serverResponseCode !=0){
                                System.out.println("[SERVER] Communication error :C please re-enter your password.");
                            }else{break;}
                        }

                        multicastListener = new Thread(new MulticastListener());
                        multicastListener.start();

                        System.out.println("[SERVER] Success! You are now logged in your account.");
                    }

                    //LOGOUT
                    else if(user.contentEquals("logout")) {
                        if(!connessione){
                            System.out.println("Client isn't logged in, can't perform logout");
                            continue;
                        }

                        user_server_response();
                        if(serverResponseCode ==1){
                            System.out.println("Client isn't logged in, can't perform logout");
                        } else if (serverResponseCode !=0) {
                            System.out.println("[SERVER] Communication error :C please retry to logout.");
                        }else{
                            System.out.println("[SERVER] Success! You have logged off from your account.");
                        }
                        //USR-END
                    }

                    //REGISTER
                    else if(user.contentEquals("register")){
                        if(!connessione){
                            if(!connect(SERVER_IP, SERVER_PORT))continue;
                        }
                        user_server_response();
                        if(serverResponseCode ==1){
                            System.out.println("[SERVER] It is not possibile to register while being logged in.");
                            continue;
                        }
                        //USR-END

                        //USERNAME PHASE
                        while(true){
                            username_password(0);
                            if(serverResponseCode ==1){
                                System.out.println("[SERVER] Username already existing! :/");
                            }else if(serverResponseCode !=0){
                                System.out.println("[SERVER] Communication error :C please retry to enter a username.");
                            }else{break;}
                        }

                        //PASSWORD PHASE
                        while (true) {
                            username_password(1);
                            if(serverResponseCode ==1){
                                System.out.println("[SERVER] You know a blank password isn't really that secure right ? ಠ_ಠ Please choose another one.");
                            }else if(serverResponseCode !=0){
                                System.out.println("[SERVER] Communication error :C please retry to enter a password.");
                            }else{break;}
                        }
                        System.out.println("[SERVER] Success! You have now registered an account. You can now login with your credentials typing the command.");
                    }

                    //STATISTICS
                    else if (user.contentEquals("statistics")) {
                        if(!connessione){
                            if(!connect(SERVER_IP, SERVER_PORT))continue;
                        }

                        user_server_response();
                        if(serverResponseCode ==1){
                            System.out.println("[SERVER] It is not possibile to show statistics while logged off.");
                            continue;
                        }else if(serverResponseCode !=0){
                            System.out.println("[SERVER] Communication error :C you can now retry to see your statistics.");
                        }
                        //USR-END

                        //Parsing and showings statistics
                        JsonReader reader = new JsonReader(new BufferedReader(new InputStreamReader(conn.getInputStream())));
                        Stat stat = gson.fromJson(reader,statType);
                        System.out.println("Played Wordles: "+stat.playedWordles);
                        System.out.println("Current Streak: "+stat.currentStreak);
                        System.out.println("Last Streak: "+stat.lastStreak);
                        System.out.println("Max Streak: "+stat.maxStreak);
                        System.out.println("Win Ratio: "+stat.winRatio);
                        System.out.println("Guess Distibution: "+stat.guessDistribution);
                    }

                    //PLAY
                    else if (user.contentEquals("play")) {
                        if(!connessione){
                            if(!connect(SERVER_IP, SERVER_PORT))continue;
                        }
                        user_server_response();
                        if(serverResponseCode ==1){
                            System.out.println("[SERVER] It is not possibile to play while logged off.");
                            continue;
                        } else if (serverResponseCode ==2) {
                            System.out.println("[SERVER] You already tried to guess today's word ಠ_ಠ.");
                            continue;
                        } else if(serverResponseCode !=0) {
                            System.out.println("[SERVER] Communication error :C you can now retry to play.");
                        }
                        //USR-END


                        System.out.println("----------------------------------------------------------------------------------------");
                        System.out.println(in.nextLine());
                        System.out.println("To send your guesses type \"send [your guess]\"");
                        System.out.println("No other command will be accepted except for \"logout\", however, you will automatically lose and your statistics will be updated regularly");
                        System.out.println("Good luck!");
                        String serverResponse;
                        boolean end = false;
                        while(true){
                            //checks if a guess can be made
                            serverResponseCode = Integer.parseInt(in.nextLine());
                            if(serverResponseCode!=0)break;

                            while(true){
                                System.out.println("Please enter a guess");
                                System.out.print(">");
                                out.println(console.nextLine());

                                //Server parsed the guess and responded accordingly
                                serverResponseCode = Integer.parseInt(in.nextLine());
                                if (serverResponseCode==0)break;
                                if(serverResponseCode==1){
                                    System.out.println("Your guess contains the wrong number of letters or it may be made of something that isn't a letter!");
                                    continue;
                                } else if (serverResponseCode==2) {
                                    System.out.println("The word is not contained inside the vocabulary!");
                                    continue;
                                } else if (serverResponseCode==3) {
                                    System.out.println("Server accepted your logoff request!");
                                    end = true;
                                    break;
                                } else if (serverResponseCode==4) {
                                    System.out.println("Server did not recognize command!");
                                }else{
                                    System.out.println("[SERVER] Communication error :C you can now retry to guess.");
                                }
                            }
                            //If logoff
                            if(end)break;

                            //Show hint
                            serverResponse = in.nextLine();
                            System.out.println(serverResponse);
                        }
                        //If logoff
                        if(end)continue;

                        //Wordle finished, results based on serverResponseCode
                        if(serverResponseCode==1){
                            System.out.println("CONGRATULATIONS! YOU WON!!!");
                        }else if(serverResponseCode==2){
                            System.out.println("...mmm, I guess you tried after all...you lost.");
                        }

                        //Choosing between sharing results with other players or exiting
                        System.out.println("Do you want to share today's results with your fellow wordlers?");
                        System.out.println("(Type in one of the following commands)\nshare\nexit");
                        while (true){
                            System.out.print(">");
                            user = console.nextLine();
                            if(user.contentEquals("share") || user.contentEquals("exit")){
                                break;
                            }else{
                                System.out.println("[ERROR] Command not recognized");
                            }
                        }
                        out.println(user);
                        serverResponseCode = Integer.parseInt(in.nextLine());

                        //Checking the result of sharing
                        switch (serverResponseCode){
                            case 0:
                                break;
                            case 1:
                                System.out.println("[SERVER] An error occured while sharing the results :/");
                                break;
                            default:
                                System.out.println("[SERVER] An error occured while transmitting the results of sharing :/");
                        }
                    }

                    //SHOWMESHARING
                    else if(user.contentEquals("showMeSharing")){
                        show_shared_results();
                    }
                    else{
                        System.out.println("[CLIENT] THe command has not been recognised, please try again!");
                    }
                    System.out.println();

                }catch (IllegalStateException | NoSuchElementException e){
                    System.out.println("[CLIENT] ERROR Connection with server lost, unfinished games will be considered as played. You'll need to re-login");
                    disconnect();
                }catch (Exception e){
                    System.out.println("[CLIENT] ERROR An error has occured in the client, closing.");
                    e.printStackTrace();
                    break;
                }

            }

        }catch (Exception e){
            e.printStackTrace();
        }
    }
}