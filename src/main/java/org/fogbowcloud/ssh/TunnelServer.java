package org.fogbowcloud.ssh;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.sshd.SshServer;
import org.apache.sshd.common.ForwardingFilter;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.Service;
import org.apache.sshd.common.Session;
import org.apache.sshd.common.Session.AttributeKey;
import org.apache.sshd.common.SshdSocketAddress;
import org.apache.sshd.common.session.AbstractSession;
import org.apache.sshd.common.util.Buffer;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.UserAuth;
import org.apache.sshd.server.auth.UserAuthNone;
import org.apache.sshd.server.command.UnknownCommand;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerConnectionService;
import org.apache.sshd.server.session.ServerSession;

public class TunnelServer {

	private static final Logger LOGGER = Logger.getLogger(TunnelServer.class);
	
	private static final long TOKEN_EXPIRATION_CHECK_INTERVAL = 30L; // 30s in seconds
	private static final int TOKEN_EXPIRATION_TIMEOUT = 1000 * 60 * 10; // 10min in ms
	
	private static final AttributeKey<String> TOKEN = new AttributeKey<String>();
	private final Map<String, Token> tokens = new ConcurrentHashMap<String, Token>();
	private ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
	
	static class Token {
		Integer port;
		Long lastIdleCheck = 0L;
		public Token(Integer port) {
			this.port = port;
		}
	}
	
	private SshServer sshServer;
	private String sshTunnelHost;
	private final int sshTunnelPort;
	private final int lowerPort;
	private final int higherPort;
	private String hostKeyPath;
	private Long idleTokenTimeout;
	
	private int nioWorkers;
	
	public TunnelServer(String sshTunnelHost, int sshTunnelPort, int lowerPort, 
			int higherPort, Long idleTokenTimeout, String hostKeyPath) {
		this.sshTunnelHost = sshTunnelHost;
		this.sshTunnelPort = sshTunnelPort;
		this.lowerPort = lowerPort;
		this.higherPort = higherPort;
		this.idleTokenTimeout = idleTokenTimeout == null ? TOKEN_EXPIRATION_TIMEOUT
				: idleTokenTimeout;
		this.hostKeyPath = hostKeyPath;
		this.nioWorkers = (higherPort - lowerPort)+2; //+2 is to have a secure margin of works for ports. If number of ports is 5, workers will be set to 6;
	}

	public synchronized Integer createPort(String token) {
		Integer newPort = null;
		if (tokens.containsKey(token)) {
			return tokens.get(token).port;
		}
		for (int port = lowerPort; port <= higherPort; port++) {
			if (isTaken(port)) {
				continue;
			}
			newPort = port;
			break;
		}
		if (newPort == null) {
			LOGGER.debug("Token [" + token + "] didn't get any port. All ports are busy.");
			return null;
		}
		
		LOGGER.debug("Token [" + token + "] got port [" + newPort + "].");
		tokens.put(token, new Token(newPort));
		return newPort;
	}
	
	private boolean isTaken(int port) {
		for (Token token : tokens.values()) {
			if (token.port.equals(port)) {
				return true;
			}
		}
		return false;
	}

	private ReverseTunnelForwarder getActiveSession(int port) {
		List<AbstractSession> activeSessions = sshServer.getActiveSessions();
		for (AbstractSession session : activeSessions) {
			Service rawService = ((ReverseTunnelSession)session).getService();
			if (rawService == null) {
				continue;
			}
			if (!(rawService instanceof ServerConnectionService)) {
				continue;
			}
			ServerConnectionService service = (ServerConnectionService) rawService;
			ReverseTunnelForwarder f = (ReverseTunnelForwarder) service.getTcpipForwarder();
			for (SshdSocketAddress address : f.getLocalForwards()) {
				if (address.getPort() == port) {
					return f;
				}
			}
		}
		return null;
	}

	public void start() throws IOException {
		this.sshServer = SshServer.setUpDefaultServer();
		SimpleGeneratorHostKeyProvider keyPairProvider = new SimpleGeneratorHostKeyProvider(hostKeyPath);
		keyPairProvider.loadKeys();
		sshServer.setKeyPairProvider(keyPairProvider);
		sshServer.setCommandFactory(createUnknownCommandFactory());
		LinkedList<NamedFactory<UserAuth>> userAuthenticators = new LinkedList<NamedFactory<UserAuth>>();
		
		userAuthenticators.add(new NamedFactory<UserAuth>(){
			@Override
			public UserAuth create() {
				return new UserAuthNone() {
					@Override
					public Boolean auth(ServerSession session, String username,
							String service, Buffer buffer) throws Exception {
						if (!tokens.containsKey(username)) {
							session.close(true);
							return false;
						}
						session.setAttribute(TOKEN, username);
						return true;
					}
				};
			}

			@Override
			public String getName() {
				return "none";
			}});
		
		sshServer.setTcpipForwardingFilter(createAcceptAllFilter());
		sshServer.setTcpipForwarderFactory(new ReverseTunnelForwarderFactory());
		sshServer.setSessionFactory(new ReverseTunnelSessionFactory());
		sshServer.setUserAuthFactories(userAuthenticators);
		sshServer.setHost(sshTunnelHost == null ? "0.0.0.0" : sshTunnelHost);
		sshServer.setPort(sshTunnelPort);
		sshServer.setNioWorkers(nioWorkers);
		executor.scheduleWithFixedDelay(new Runnable() {
			@Override
			public void run() {
				Set<String> tokensToExpire = new HashSet<String>();
				for (Entry<String, Token> tokenEntry : tokens.entrySet()) {
					Token token = tokenEntry.getValue();
					if (getActiveSession(token.port) == null) {
						long now = System.currentTimeMillis();
						if (token.lastIdleCheck == 0) {
							token.lastIdleCheck = now;
						}
						if (now - token.lastIdleCheck > idleTokenTimeout) {
							tokensToExpire.add(tokenEntry.getKey());
						}
					} else {
						token.lastIdleCheck = 0L;
					}
				}
				for (String token : tokensToExpire) {
					LOGGER.debug("Expiring token [" + token + "].");
					tokens.remove(token);
				}
			}
		}, 0L, TOKEN_EXPIRATION_CHECK_INTERVAL, TimeUnit.SECONDS);
		sshServer.start();
	}

	private static CommandFactory createUnknownCommandFactory() {
		return new CommandFactory() {
			@Override
			public Command createCommand(String command) {
				return new UnknownCommand(command);
			}
		};
	}

	private ForwardingFilter createAcceptAllFilter() {
		return new ForwardingFilter() {
			@Override
			public boolean canListen(SshdSocketAddress address, Session session) {
				String username = session.getAttribute(TOKEN);
				if (username == null) {
					session.close(true);
					return false;
				}
				Token token = tokens.get(username);
				if (token == null || !token.port.equals(address.getPort())) {
					session.close(true);
					return false;
				}
				ReverseTunnelForwarder existingSession = getActiveSession(token.port);
				if (existingSession != null) {
					existingSession.close(true);
				}
				return true;
			}
			
			@Override
			public boolean canForwardX11(Session session) {
				return false;
			}
			
			@Override
			public boolean canForwardAgent(Session session) {
				return true;
			}
			
			@Override
			public boolean canConnect(SshdSocketAddress address, Session session) {
				return true;
			}
		};
	}

	public Integer getPort(String tokenId) {
		Token token = tokens.get(tokenId);
		if (token == null) {
			return null;
		}
		return token.port;
	}
	
	public Map<String, Integer> getAllPorts() {
		Map<String, Integer> portsByPrefix = new HashMap<String, Integer>();
		for (Entry<String, Token> tokenEntry : tokens.entrySet()) {
			portsByPrefix.put(
					tokenEntry.getKey(), 
					tokenEntry.getValue().port);
		}
		return portsByPrefix;
	}
	
	public Map<String, Integer> getPortByPrefix(String tokenId) {
		Map<String, Integer> portsByPrefix = new HashMap<String, Integer>();
		Integer sshPort = getPort(tokenId);
		if (sshPort != null) {
			portsByPrefix.put("ssh", sshPort);
		}
		for (Entry<String, Token> tokenEntry : tokens.entrySet()) {
			String tokenPrefix = tokenId + "-";
			if (tokenEntry.getKey().startsWith(tokenPrefix)) {
				portsByPrefix.put(
						tokenEntry.getKey().substring(tokenPrefix.length()), 
						tokenEntry.getValue().port);
			}
		}
		return portsByPrefix;
	}
	
	//TODO: Create a method that return boolean for server busy (reached port limit) or not.
	public boolean isServerBusy(){
		for (int port = lowerPort; port <= higherPort; port++) {
			if (!isTaken(port)) {
				return false;
			}
		}
		return true;
	}
	
	//TODO: Create a new method to remove a token and release the relative port. 
	public void removeToken(String tokenId){
		tokens.remove(tokenId);
		
	}
	
	public void releasePort(Integer port){
		if(port != null){
			String tokenToRemove = null;
			for(Entry<String, Token> e : tokens.entrySet()){
				if(port.compareTo(e.getValue().port) == 0){
					tokenToRemove = e.getKey();
					break;
				}
			}
			
			if(this.getActiveSession(port.intValue()) != null){
				this.getActiveSession(port.intValue()).close(true);
			}
			tokens.remove(tokenToRemove);
		}
	}
	
	public void stop() throws InterruptedException{
		
		List<AbstractSession> activeSessions = sshServer.getActiveSessions();
		if(activeSessions != null && !activeSessions.isEmpty()){
			for (AbstractSession session : activeSessions) {
				session.close(true);
			}
		}
		sshServer.stop(true);
		
	}

	public int getActiveTokensNumber(){
		return tokens.size();
	}
	
	public int getLowerPort() {
		return lowerPort;
	}

	public int getHigherPort() {
		return higherPort;
	}

	public int getSshTunnelPort() {
		return sshTunnelPort;
	}
	
}