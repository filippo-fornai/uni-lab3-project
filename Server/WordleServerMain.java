import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;

import java.net.DatagramSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

public class WordleServerMain {
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

    //Class relative to a single player, used in database to store info about all players.
    static class Player{
        String password;
        Stat stat;
        Player(String password, Stat stat){
            this.password = password;
            this.stat = stat;
        }
    }

    //DATABASE----------------------------------------------------------------------------------------------------------
    /*
        Class that stores info about players. In particular all statistics, current online
        players and players that have already played today are stored here.
        The class also offers methods to retrieve and modify all data cited above in a
        thread-safe if required
     */

    static class Database{
        private static ConcurrentHashMap<String,Player> data;
        private static final Set<String> online_players = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private static final Set<String> played_players = Collections.newSetFromMap(new ConcurrentHashMap<>());
        Database(){data = new ConcurrentHashMap<>();}

        //The following methods are grouped based on what they operate and which classes they use

        //Methods that operate on "data" at "Player class" level
        public static String register(Scanner in, PrintWriter out){
            String username,password;

            while (true){
                username = in.nextLine();
                if (!Database.data.containsKey(username)) break;
                out.println("1");
            }
            out.println("0");

            while (true){
                password = in.nextLine();
                if(!password.contentEquals(""))break;
                out.println("1");
            }
            out.println("0");
            Database.data.put(username, new Player(password, new Stat(MAX_TRIES)));

            return username;


        }

        public static String login(Scanner in,PrintWriter out){
            String username,password;
            while (true){
                username = in.nextLine();
                if(Database.data.containsKey(username))break;
                out.println("1");
            }
            if (online_players.contains(username)) {
                out.println("2");
                return null;
            }

            out.println("0");


            while (true){
                password = in.nextLine();
                if (Database.data.get(username).password.contentEquals(password)) break;
                out.println("1");
            }
            out.println("0");

            return username;

        }

        //Methods that operate on "data" at "Stat class" level
        public static Stat get_stat(String username){
            return Database.data.get(username).stat;
        }

        public static void lose(String username){
            Stat stat;
            stat = Database.data.get(username).stat;
            if(stat.currentStreak>0)stat.lastStreak=stat.currentStreak;
            stat.currentStreak=0;
            stat.winRatio=(stat.winRatio * stat.playedWordles) / (++stat.playedWordles);
        }

        public static void win(String username, int tries){
            Stat stat = Database.data.get(username).stat;
            stat.currentStreak++;
            if (stat.currentStreak>stat.maxStreak)stat.maxStreak=stat.currentStreak;
            stat.winRatio=((stat.winRatio * stat.playedWordles)+1) / (++stat.playedWordles);
            stat.guessDistribution.set(tries-1,stat.guessDistribution.get(tries-1)+1);
        }
    }

    //SERVER-UTILITIES--------------------------------------------------------------------------------------------------
    //Support method to parse "ServerSettings.json" and load settings
    public static boolean loadSettings(){
        try {
            JsonReader supp = new JsonReader(new FileReader("ServerSettings.json"));
            supp.beginObject();

            //region SERVER_PORT
            //Parsing "server_port" field
            if (!supp.nextName().contentEquals("server_port")){
                System.out.println("[SERVER] ERROR server_port field is absent");
                return false;
            }
            try{
                SERVER_PORT = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"server_port\" is not a parsable string that represents a PORT");
                return false;
            }
            if (SERVER_PORT <= 0) SERVER_PORT = 10000;
            //endregion

            //region MULTICAST_IP
            //Parsing "multicast_ip"
            if(!supp.nextName().contentEquals("multicast_ip")){
                System.out.println("[SERVER] ERROR multicast_ip field is absent");
                return false;
            }
            MULTICAST_IP = supp.nextString();

            if(MULTICAST_IP.contentEquals("0")){
                MULTICAST_IP = "224.0.0.0";
            }else{
                String suppStr[];
                if(MULTICAST_IP ==null || MULTICAST_IP.isEmpty() || (suppStr= MULTICAST_IP.split("\\.")).length!=4 || MULTICAST_IP.endsWith(".")){
                    System.out.println("[SERVER] ERROR Value of field \"multicast_ip\" is not a parsable string that represents an IP");
                    return false;
                }
                int a;
                for(String part : suppStr){
                    a = Integer.parseInt(part);
                    if(a<0 || a>255){
                        System.out.println("[SERVER] ERROR Value of field \"multicast_ip\" is not a parsable string that represents an IP");
                        return false;
                    }
                }
            }
            //endregion

            //region MULTICAST_PORT
            //Parsing "multicast_port"
            if (!supp.nextName().contentEquals("multicast_port")){
                System.out.println("[SERVER] ERROR multicast_port field is absent");
                return false;
            }
            try{
                MULTICAST_PORT = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"multicast_port\" is not a parsable string that represents a PORT");
                return false;
            }
            if (MULTICAST_PORT <= 0) MULTICAST_PORT = 10000;
            //endregion

            //region LETTERS
            //Parsing "letters"
            if (!supp.nextName().contentEquals("letters")){
                System.out.println("[SERVER] ERROR letters field is absent");
                return false;
            }
            try{
                LETTERS = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"letters\" is not a parsable string that represents a number of letters");
                return false;
            }
            if (LETTERS <= 0) LETTERS = 5;
            //endregion

            //region MAX_TRIES
            //Parsing "max_tries"
            if (!supp.nextName().contentEquals("max_tries")){
                System.out.println("[SERVER] ERROR max_tries field is absent");
                return false;
            }
            try{
                MAX_TRIES = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"max_tries\" is not a parsable string that represents a number of max_tries");
                return false;
            }
            if (MAX_TRIES <= 0) MAX_TRIES = 6;
            //endregion

            //region N_THREADS
            //Parsing "n_threads"
            if (!supp.nextName().contentEquals("n_threads")){
                System.out.println("[SERVER] ERROR n_threads field is absent");
                return false;
            }
            try{
                N_THREADS = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"n_threads\" is not a parsable string that represents a number of operating threads");
                return false;
            }
            if (N_THREADS <= 0) N_THREADS = 3;
            //endregion

            //region RESET_TIME
            //Parsing "reset_time"
            if (!supp.nextName().contentEquals("reset_time")){
                System.out.println("[SERVER] ERROR reset_time field is absent");
                return false;
            }
            try{
                RESET_TIME = Integer.parseInt(supp.nextString());
            }catch(NumberFormatException e){
                System.out.println("[SERVER] ERROR Value of field \"reset_time\" is not a parsable string that represents a time interval");
                return false;
            }
            if (RESET_TIME <= 0) RESET_TIME = 86400;
            //endregion

            supp.endObject();
            supp.close();
            return true;


        }catch(FileNotFoundException e) {
            System.out.println("[SERVER] ERROR ServerSettings.json absent");
            return false;
        }
        catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    //Support method to parse "Data.json" and load all players data
    public static boolean loadData(){
        try{
            JsonReader reader = new JsonReader(new FileReader("Data.json"));
            Gson gson = new Gson();
            Type dataType = new TypeToken<ConcurrentHashMap<String,Player>>() {}.getType();
            Database.data = gson.fromJson(reader,dataType);
            if(Database.data==null)Database.data = new ConcurrentHashMap<>();
            reader.close();
        }catch (JsonSyntaxException e){
            System.out.println("[SERVER] ERROR Data.json is not in a recognised format ");
            return false;
        } catch(FileNotFoundException e) {
            System.out.println("[SERVER] ERROR Data.json absent ");
            return false;
        } catch ( Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }

    //Support method to check if "words.txt" is written correctly and readable
    public static boolean checkVocabulary(){
        try{
            String supp1,supp2;
            int counter=1;
            Scanner reader = new Scanner(new BufferedInputStream(new FileInputStream("words.txt")));
            if((supp1=reader.nextLine()) == null){
                System.out.println("[SERVER] ERROR words.txt does not contain any words ");
                return false;
            }
            if(supp1.length() != LETTERS){
                System.out.println("[SERVER] ERROR words.txt contains word with the wrong number of letters at line "+counter);
            }
            while(reader.hasNextLine()){
                supp2 = reader.nextLine();
                counter++;
                if(supp2.length() == LETTERS){
                    if(supp1.compareTo(supp2)>0){
                        System.out.println("[SERVER] ERROR words.txt contains words that are not ordered lexicographically");
                        return false;
                    }
                }else{
                    System.out.println("[SERVER] ERROR words.txt contains word with the wrong number of letters at line "+counter);
                    return false;
                }


                supp1=supp2;
            }

            vocabulary = new RandomAccessFile("words.txt","r");
            vocabulary_length = vocabulary.length() / (LETTERS+1);

            return true;
        }catch(FileNotFoundException e) {
            System.out.println("[SERVER] ERROR words.txt absent");
            return false;
        }catch (Exception e){
            e.printStackTrace();
            return false;
        }
    }
    //Support method to save all players data in "Data.json"
    public static boolean unloadData(){
        try{
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonWriter writer = new JsonWriter(new PrintWriter("Data.json"));
            Type dataType = new TypeToken<SortedMap<String,Player>>() {}.getType();
            gson.toJson(Database.data,dataType,writer);
            writer.close();
        }catch(Exception e){
            e.printStackTrace();
            return false;
        }
        return true;
    }


    //CLIENT------------------------------------------------------------------------------------------------------------
    /*
        Client class of which instances are run by a thread-pool. The class represents a "client" that has connected to the server.
        The client can log off and login whenever it likes.
        The time between a login and a logoff is called a "session".
        The connection to the server will always persist
        until the client closes the connection or the server is shut down.
        (Meaning the sessions have no impact on the connection)
     */

    public static class Client implements Runnable{
        String  username;       //username associated with the session of the client

        boolean logged=false;   //support flags associated with the session
        boolean playing=false;

        Socket socket;          //Properties associated with the "connection"
        PrintWriter out;
        Scanner in;

        Gson gson;              //Support properties to send statistics
        Type statType;

        //Constructor that initialises all support properties and properties associated with the connection
        Client(Socket socket){
            this.socket = socket;
            try{
                out = new PrintWriter(socket.getOutputStream(),true);
                in = new Scanner(socket.getInputStream());
                gson = new GsonBuilder().setPrettyPrinting().create();
                statType = new TypeToken<Stat>(){}.getType();
            }catch (Exception e){
                e.printStackTrace();
            }
        }

        //Support method to share the statistics on the chosen multicast channel
        synchronized public static boolean share(String results){
            try{
                byte[] msg = results.getBytes();
                DatagramPacket buffer = new DatagramPacket(msg,msg.length,group,MULTICAST_PORT);
                buffer.setData(msg,0,msg.length);
                buffer.setLength(msg.length);
                datagramSocket.send(buffer);
                return true;
            }catch(Exception e){
                return false;
            }

        }

        @Override
        public void run() {
            try{
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
                String user;
                while(true) {
                    //USR-BEGIN
                    user = in.nextLine();

                    //LOGIN
                    if(user.contentEquals("login")){
                        if(logged){
                            out.println("1");
                        }else{
                            out.println("0");
                            //USR-END

                            username = Database.login(in,out);
                            if(username == null)continue;

                            Database.online_players.add(username);
                            logged = true;
                        }
                    }

                    //LOGOUT
                    else if(user.contentEquals("logout")){
                        if(!logged){
                            out.println("1");
                        }else{
                            out.println("0");
                            //USR-END

                            Database.online_players.remove(username);
                            username = null;
                            logged = false;
                        }
                    }

                    //REGISTER
                    else if (user.contentEquals("register")) {
                        if(logged){
                            out.println("1");
                        }else{
                            out.println("0");
                            //USR-END

                            username = Database.register(in,out);
                        }
                    }

                    //STATISTICS
                    else if (user.contentEquals("statistics")) {
                        if(!logged){
                            out.println("1");
                        }else{
                            out.println("0");
                            //USR-END

                            //Takes a copy of the statistics associated with the user and sends them to the client
                            JsonWriter writer = new JsonWriter(new PrintWriter(out));
                            gson.toJson(Database.get_stat(username),statType,writer);
                            writer.flush();
                        }
                    }

                    //PLAY
                    else if (user.contentEquals("play")) {
                        if(!logged) {
                            out.println("1");
                        }else {
                            if (Database.played_players.contains(username)) {
                                out.println("2");
                                continue;
                            } else {
                                out.println("0");
                            }
                            //USR-END

                            //Playing status = true
                            playing = true;

                            out.println("Today's word is made out of " + LETTERS + " letters!");

                            //Support variables
                            String wotd = wordOfTheDay;
                            int wordle_number = N_WORDLES.get();
                            Integer[][] supp = new Integer[MAX_TRIES][LETTERS];
                            boolean win=false,end=false;
                            String[] parsing;
                            int tries = 0;

                            for (int j = 0; j < MAX_TRIES; j++) {
                                //Confirms that a guess can be made or logout can be executed
                                out.println("0");

                                //Parsing command and server response to command
                                while (true) {
                                    parsing = in.nextLine().split("\\s+");

                                    if(parsing.length==2 && parsing[0].contentEquals("send")){
                                        if (parsing[1].length() == LETTERS && parsing[1].matches("[a-zA-Z]+")) {
                                            if (inVocabulary(parsing[1])) break;
                                            out.println("2");
                                        } else {
                                            out.println("1");
                                        }
                                    } else if (parsing.length==1 && parsing[0].contentEquals("logout")) {
                                        out.println("3");
                                        end=true;
                                        break;
                                    }
                                    else{
                                        out.println("4");
                                    }

                                }

                                //if command="logout"
                                if(end)break;

                                //After a correctly formatted guess the word is parsed to associate the correct colors
                                //to all the letters (charArr contains 0:correct letter,1:letter in word of the day,
                                //2 absent from word of the day)
                                tries++;
                                out.println("0");
                                char[] charArr = parsing[1].toCharArray();

                                for (int i = 0; i < wotd.length(); i++) {

                                    if (charArr[i] == wotd.charAt(i)) supp[j][i] = 0;
                                    else if (wotd.indexOf(charArr[i]) != -1) supp[j][i] = 1;
                                    else supp[j][i] = 2;
                                }

                                //After associating the colors the hint is built
                                String result= "";
                                for(int i = 0;i<wotd.length();i++){
                                    String letter;
                                    if(supp[j][i]==0){
                                        letter = "\u001b[7m"+"\u001b[32m"+charArr[i]+"\u001b[0m";
                                    } else if (supp[j][i] == 1) {
                                        letter = "\u001b[7m"+"\u001b[33m"+charArr[i]+"\u001b[0m";
                                    }else{
                                        letter = "\u001b[7m"+ charArr[i] +"\u001b[0m";
                                    }
                                    result = result.concat(letter);
                                }

                                //The hint is sent
                                //If the client correctly guessed the word the cycle breaks.
                                out.println(result);
                                win=true;
                                for(int i = 0;i<wotd.length();i++)if(supp[j][i]!=0){
                                    win=false;
                                    break;
                                }
                                if(win)break;
                            }

                            //if command="logout"
                            if(end){
                                Database.online_players.remove(username);
                                Database.lose(username);
                                if(wotd.equals(wordOfTheDay))Database.played_players.add(username);
                                username = null;
                                logged=false;
                                playing=false;
                                continue;
                            }

                            //The client at this point will wait for a response to know the game has finished.
                            //(the first out.println("0") on the previous cycle iterating MAX_TRIES times served the purpose
                            //of confirming a guess could be made, because the client ALWAYS waits the server before making a guess
                            // it can be used to send a code signaling the end of the wordle)
                            if(win){
                                out.println("1");
                                Database.win(username,tries);
                            }else{
                                out.println("2");
                                Database.lose(username);
                            }
                            playing = false;
                            if(wotd.equals(wordOfTheDay))Database.played_players.add(username);

                            //At this point server waits client decision on sharing and eventually the results are shared
                            user = in.nextLine();
                            if(user.contentEquals("share")){
                                String result="WORDLE "+wordle_number+" "+tries+"/"+MAX_TRIES+"\n";
                                for(int j = 0; j < tries; j++){
                                    for(int i = 0;i < wotd.length();i++){
                                        result = result.concat(supp[j][i].toString());
                                    }
                                    result = result.concat("\n");
                                }
                                if(share(result)){
                                    out.println("0");
                                }else{
                                    out.println("1");
                                }
                            }else{
                                out.println("0");
                            }
                        }
                    }

                    else{out.println("5");}
                }

            }catch (IllegalStateException | NoSuchElementException e){

                //Connection closed
                System.out.println("[SERVER] CONNECTION CLOSED");

                if(username !=null)Database.online_players.remove(username);
                if(playing){
                    Database.played_players.add(username);
                    Database.lose(username);
                }
                sockets.remove(socket);
                in.close();
                out.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    //SERVER------------------------------------------------------------------------------------------------------------

    //SERVER PARAMETERS
    static volatile int LETTERS,MAX_TRIES,N_THREADS,RESET_TIME;
    static final AtomicInteger N_WORDLES = new AtomicInteger(1);

    //SOCKETS : properties and methods
    static volatile int SERVER_PORT;
    static ServerSocket socket;
    /*
        Inside CLient class it has been chosen to leave blocking sockets. As this method doesn't offer "on-demand" exit
        when main thread receives "exit" command it has been chosen to save references to sockets inside the underneath
        SynchronizedList and close them when needed, forcing threads to exit after receiving an exception.
     */
    static List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());


    //MULTICAST: properties
    static volatile String MULTICAST_IP;
    static volatile int MULTICAST_PORT;
    static volatile DatagramSocket datagramSocket;
    static volatile InetAddress group;


    //VOCABULARY: properties and methods
    static volatile RandomAccessFile vocabulary;
    static volatile long vocabulary_length;
    static volatile String wordOfTheDay = "";

    public static void changeWOTD(){
        try{
            vocabulary.seek(ThreadLocalRandom.current().nextLong(vocabulary_length)*(LETTERS+1));
            byte[] supp = new byte[LETTERS];
            vocabulary.read(supp);
            wordOfTheDay = new String(supp);
            System.out.println("\u001B[35m[SERVER]\u001B[0m WORD OF THE DAY CHANGED TO: \u001B[33m"+wordOfTheDay+"\u001B[0m");
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    synchronized public static boolean inVocabulary(String word){
        try{
            //support
            RandomAccessFile supp = new RandomAccessFile("words.txt","r");
            int result;
            byte[] arr = new byte[LETTERS];

            //Binary search
            long a=0,b,c=vocabulary_length;
            while(a<=c){
                b=(long)Math.floor(((double)a + (double)c) / 2);

                //support to fetch and compare word in file
                supp.seek(b*(LETTERS+1));
                supp.read(arr);
                String supp_str = new String(arr);
                result = word.compareTo(supp_str);

                if(result==0){
                    return true;
                }else if(result < 0){
                    c=b-1;
                }else{
                    a=b+1;
                }
            }
            return false;
        }catch (IOException e){
            e.printStackTrace();
        }
        return true;
    }

    //WORD-CHANGER------------------------------------------------------------------------------------------------------
    //Task that changes everytime RESET_TIME seconds the word of the "day"
    public static class WordChanger implements Runnable{
        @Override
        public void run() {
            try {
                changeWOTD();
                Database.played_players.clear();
                N_WORDLES.getAndAdd(1);
            }catch (Exception e){
                e.printStackTrace();
            }

        }
    }

    //SERVER-ENDER------------------------------------------------------------------------------------------------------
    //Thread that waits console input to terminate the server
    static volatile boolean end=false;

    public static class ServerEnder implements Runnable{
        @Override
        public void run() {
            Scanner console =new Scanner(System.in);
            while(true){
                if (console.nextLine().contentEquals("exit"))break;
            }
            System.out.println("\u001B[35m[SERVER]\u001B[0m EXIT ISSUED");
            end=true;
            System.out.println("\u001B[35m[SERVER]\u001B[0m SERVER ENDER THREAD CLOSING");
        }
    }


    //MAIN--------------------------------------------------------------------------------------------------------------
    public static void main(String[] args) {
        //SERVER INIT
        System.out.println("[SERVER] STARTING");
        if(!loadSettings())return;
        System.out.println("[SERVER] SETTINGS LOADED");
        if(!checkVocabulary())return;
        System.out.println("[SERVER] VOCABULARY CHECKED");
        if(!loadData())return;
        System.out.println("[SERVER] DATA LOADED");

        try{
            //SOCKET INIT
            socket = new ServerSocket(SERVER_PORT);
            socket.setSoTimeout(10000);
            sockets = new ArrayList<>(N_THREADS);
            System.out.println("[SERVER] TCP ACCEPT SOCKET INITIALIZED");

            //MULTICAST INIT
            datagramSocket = new DatagramSocket(0);
            datagramSocket.setBroadcast(true);
            group = InetAddress.getByName(MULTICAST_IP);
            System.out.println("[SERVER] UDP MULTICAST SOCKET INITIALIZED");

            //WORD-CHANGER INIT
            changeWOTD();
            N_WORDLES.set(0);
            ScheduledExecutorService wordChanger = Executors.newSingleThreadScheduledExecutor();
            wordChanger.scheduleWithFixedDelay(new WordChanger(),RESET_TIME,RESET_TIME, TimeUnit.SECONDS);
            System.out.println("[SERVER] WORD CHANGER THREAD SCHEDULED");

            //SERVER-ENDER INIT
            Thread serverEnder = new Thread(new ServerEnder());
            serverEnder.start();
            System.out.println("[SERVER] SERVER ENDER THREAD STARTED");
            System.out.println("[SERVER] IT IS NOW POSSIBLE TO SHUT DOWN THE SERVER TYPING THE WORD \"exit\"");

            //THREADPOOL INIT
            ExecutorService pool = Executors.newFixedThreadPool(N_THREADS);
            System.out.println("[SERVER] THREAD POOL STARTED");

            //ACCEPTING CONNECTIONS
            System.out.println("[SERVER] SERVER READY TO ACCEPT CONNECTIONS");
            while(!end) {
                try {
                    Socket accept = socket.accept();
                    sockets.add(accept);
                    pool.execute(new Client(accept));
                    System.out.println("[SERVER] CONNECTION ACCEPTED - NEW THREAD SPAWNED");
                }catch (SocketTimeoutException e){}
            }

            //ALL SOCKETS CLOSE
            socket.close();
            for(Socket s : sockets){
                s.close();
            }

            //THREADPOOL CLOSE
            pool.shutdown();
            while(!pool.awaitTermination(2,TimeUnit.SECONDS)){
                System.out.println("errore");
            };

            //WORDCHANGER CLOSE
            wordChanger.shutdown();
            while(!wordChanger.awaitTermination(2,TimeUnit.SECONDS)){
                System.out.println("errore");
            };
            datagramSocket.close();

        }catch(Exception e){
            e.printStackTrace();
        }

        if(!unloadData()){
            System.out.println("[SERVER] JSON SERIALIZATION ERROR - DATA MAY HAVE NOT BEEN WRITTEN ");
        }else{
            System.out.println("[SERVER] DATA SAVED");
        }
        System.out.println("[SERVER] CLOSING");

    }
}