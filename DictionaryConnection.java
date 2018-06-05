package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.exception.DictConnectionException;
import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;
import ca.ubc.cs317.dict.util.DictStringParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;

public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;

    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;

    private Map<String, Database> databaseMap = new LinkedHashMap<String, Database>();


    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {
        //define welcome code
        int welcomeCode = 220;

        try {
            //create new socket and get input from server
            socket = new Socket(host, port);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            //get status using Status class provided and make sure code is for welcome
            checkStatus(welcomeCode);
        }
        //handle I/O error
        catch (IOException e) {
            System.err.println("Could not get I/O for connection to host " + host);
        }
    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {
        try {
            //send quit command to server
            output = new PrintWriter(socket.getOutputStream(), true);
            output.println("QUIT");

            //receive final response and close socket
            String closeMessage = input.readLine();
            socket.close();
        }
        //exceptions(ignored)
        catch (IOException e) {
            System.err.println("exception ignored");
        }
    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();
        getDatabaseList(); // Ensure the list of databases has been populated

        //define status codes
        int retrieveCode = 150;
        int doneCode = 250;
        int defineCode = 151;
        int noDefineFoundCode = 552;

        try {
            //create output stream and send user data to server
            output = new PrintWriter(socket.getOutputStream(), true);

            //handle two word words
            if (word.contains(" ") && !(word.startsWith("\"")) && !(word.endsWith("\""))){
                String quotedWord = ("\"" + word + "\"");
                output.println("DEFINE " + database.getName() + " " + quotedWord);
            }
            else {
                output.println("DEFINE " + database.getName() + " " + word);
            }

            //get status, handle base case if no definition is found
            Status status = Status.readStatus(input);
            if (status.getStatusCode() == noDefineFoundCode) {
                return set;
            }

            //if correct, handle number of definitions retrieved:
            if (status.getStatusCode() == retrieveCode) {
                String statusString = status.getDetails();
                int numDef = Integer.parseInt(statusString.substring(0, statusString.indexOf(" ")));

                for (int i = 0; i < numDef;){
                    //make sure it is returning the 151 definition line, then parse the status data
                    Status defstatus = checkStatus(defineCode);
                    String[] oneFiveOneDetails = DictStringParser.splitAtoms(defstatus.getDetails());

                    //create a new definition object and fill it out
                    String databaseName = oneFiveOneDetails[1];
                    Definition define = new Definition(word, databaseMap.get(databaseName));
                    String line = input.readLine();
                    while (!(line.startsWith("."))){
                        define.appendDefinition(line);
                        line = input.readLine();
                    }
                    set.add(define);
                    i++;
                }
            }
            //check for 250 status at the end of stream
            checkStatus(doneCode);
        }
        catch (IOException e) {
            System.err.println("unable to get I/O for output stream");
        }
        return set;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();

        //define server codes
        int matchCode = 152;
        int doneCode = 250;
        int noMatchCode = 552;

        try {
            //create output stream and send user data to server
            output = new PrintWriter(socket.getOutputStream(), true);

            //handle two word words
            if (word.contains(" ") && !(word.startsWith("\"")) && !(word.endsWith("\""))){
                String quotedWord = ("\"" + word + "\"");
                output.println("MATCH " + database.getName() + " " + strategy.getName() + " " + quotedWord);
            }
            else {
                output.println("MATCH " + database.getName() + " " + strategy.getName() + " " + word);
            }

            //check status and returns empty set if no match
            Status matchStatus = Status.readStatus(input);
            if (matchStatus.getStatusCode() == noMatchCode) {
                return set;
            }

            //if it is the correct match, we continue and get the number of matches
            if (matchStatus.getStatusCode() == matchCode) {
                String[] matchList = DictStringParser.splitAtoms(matchStatus.getDetails());
                int numMatch = Integer.parseInt(matchList[0]);

                //get matches and add to the linked hash set
                for (int i = 0; i < numMatch;) {
                    String[] lineList = DictStringParser.splitAtoms(input.readLine());
                    String match = lineList[1];
                    set.add(match);
                    i++;
                }

                //skip(read) . and ensure last line is 250
                input.readLine();
                checkStatus(doneCode);
            }
        }
        catch (IOException e) {
            System.out.println("Could not find I/O");
        }

        return set;
    }

    /** Requests and retrieves a list of all valid databases used in the server. In addition to returning the list, this
     * method also updates the local databaseMap field, which contains a mapping from database name to Database object,
     * to be used by other methods (e.g., getDefinitionMap) to return a Database object based on the name.
     *
     * @return A collection of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Database> getDatabaseList() throws DictConnectionException {
        //define status codes
        int databaseCode = 110;
        int doneCode = 250;

        try {
            //create output stream and send user data to server
            output = new PrintWriter(socket.getOutputStream(), true);
            output.println("SHOW DB");

            //ensure status is correct and get number of DBs
            Status dbStatus = checkStatus(databaseCode);
            String[] dbStatusList = DictStringParser.splitAtoms(dbStatus.getDetails());
            int numDB = Integer.parseInt(dbStatusList[0]);

            //get DBs and add to databaseMap
            for (int i = 0; i < numDB;) {
                String[] lineList = DictStringParser.splitAtoms(input.readLine());
                String dbName = lineList[0];
                String dbDesc = lineList[1];

                Database database = new Database(dbName, dbDesc);
                databaseMap.put(dbName, database);
                i++;
            }

            //skip(read) . and ensure last line is 250
            input.readLine();
            checkStatus(doneCode);
        }
        catch (IOException e) {
            System.out.println("Could not find I/O");
        }

        return databaseMap.values();
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> set = new LinkedHashSet<>();
        //define status codes
        int stratCode = 111;
        int doneCode = 250;

        try {
            //create output stream and send user data to server
            output = new PrintWriter(socket.getOutputStream(), true);
            output.println("SHOW STRAT");

            //ensure status is correct and get number of strategies
            Status stratStatus = checkStatus(stratCode);
            String[] stratList = DictStringParser.splitAtoms(stratStatus.getDetails());
            int numStrat = Integer.parseInt(stratList[0]);

            //get strategies and add to MatchingStrategy set
            for (int i = 0; i < numStrat;) {
                String[] lineList = DictStringParser.splitAtoms(input.readLine());
                String stratName = lineList[0];
                String stratDesc = lineList[1];

                MatchingStrategy strategy = new MatchingStrategy(stratName, stratDesc);
                set.add(strategy);
                i++;
            }

            //skip(read) . and ensure last line is 250
            input.readLine();
            checkStatus(doneCode);
        }
        catch (IOException e) {
            System.out.println("Could not find I/O");
        }

        return set;
    }

    /** Checks if the server returns the correct status code
     * @param  code The status code to check the server status against
     *
     * @return The current status returned by the server
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    private Status checkStatus(int code) throws DictConnectionException {
        Status status = Status.readStatus(input);
        if (status.getStatusCode() != code) {
            throw new DictConnectionException();
        }
        return status;
    }

}
