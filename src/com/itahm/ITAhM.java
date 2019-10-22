package com.itahm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

import org.h2.jdbcx.JdbcConnectionPool;

import com.itahm.http.HTTPServer;
import com.itahm.http.Request;
import com.itahm.http.Response;
import com.itahm.http.Session;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.lang.KR;
import com.itahm.service.NMS;
import com.itahm.service.Serviceable;
import com.itahm.util.Util;

public class ITAhM extends HTTPServer {
	private final String MD5_ROOT = "63a9f0ea7bb98050796b649e85481845";
	private final static int SESS_TIMEOUT = 3600;
	private final Path root;
	public final int limit;
	public final long expire;
	private final JdbcConnectionPool connPool;
	private Boolean isClosed = false;
	private final ArrayList<Serviceable> services = new ArrayList<>();
	
	private ITAhM(Builder builder) throws Exception {
		super(builder.ip, builder.tcp);
		
		System.out.format("ITAhM HTTP Server started with TCP %d.\n", builder.tcp);
		
		root = builder.root.resolve("data");
		limit = builder.limit;
		expire = builder.expire;
		
		if (!Files.isDirectory(root)) {
			Files.createDirectories(root);
		}
		
		connPool = JdbcConnectionPool.create(String.format("jdbc:h2:%s", root.resolve("account").toString()), "sa", "");
		
		try (Connection c = connPool.getConnection()) {
			c.setAutoCommit(false);
		
			try {
				/**
				 * ACCOUNT
				 */
				try (Statement stmt = c.createStatement()) {
					stmt.executeUpdate("CREATE TABLE IF NOT EXISTS account"+
						" (username VARCHAR NOT NULL"+
						", password VARCHAR NOT NULL"+
						", level INT NOT NULL DEFAULT 0"+
						", PRIMARY KEY(username));");
				}
				
				try (Statement stmt = c.createStatement()) {
					try (ResultSet rs = stmt.executeQuery("SELECT COUNT(username) FROM account;")) {
						if (!rs.next() || rs.getLong(1) == 0) {
							try (PreparedStatement pstmt = c.prepareStatement("INSERT INTO account"+
								" (username, password, level)"+
								" VALUES ('root', ?, 0);")) {
								pstmt.setString(1, MD5_ROOT);
								
								pstmt.executeUpdate();
							}
						}
					}
				}
				
				c.commit();
			} catch (SQLException sqle) {
				c.rollback();
				
				throw sqle;
			}
		}
		
		services.add(new NMS(root));
		
		services.forEach(service -> service.start());
		
		if (expire > 0) {
			new Timer("Expire Timer").schedule(new TimerTask() {

				@Override
				public void run() {
					System.out.println(KR.ERROR_LICENSE_EXPIRE);
					
					close();
				}
				
			}, new Date(expire));
		}
	}

	public static class Builder {
		private String ip = "0.0.0.0";
		private int tcp = 2014;
		private Path root = null;
		private boolean licensed = true;
		private long expire = 0;
		private int limit = 0;
		
		public Builder() {
		}
		
		public Builder tcp(int i) {
			tcp = i;
			
			return this;
		}
		
		public Builder root(String path) {
			try {
				root = Path.of(path);
			}
			catch(InvalidPathException ipe) {
			}
			
			return this;
		}
		
		public Builder license(String mac) {
			if (!Util.isValidAddress(mac)) {
				System.out.println("Check your License.MAC");
				
				licensed = false;
			}
			
			return this;
		}
		
		public Builder expire(long ms) {
			
			expire = ms;
			
			return this;
		}
		
		public Builder limit(int n) {
			limit = n;
			
			return this;
		}
		
		public ITAhM build() throws Exception {
			if (!this.licensed) {
				return null;
			}
			
			if (this.root == null) {
				this.root = Path.of(ITAhM.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
			}
			
			return new ITAhM(this);
		}
	}
	
	@Override
	public void doGet(Request request, Response response) {
		String uri = request.getRequestURI();
		
		if ("/".equals(uri)) {
			uri = "/index.html";
		}
		
		Path path = this.root.resolve(uri.substring(1));
		
		if (!Pattern.compile("^/data/.*").matcher(uri).matches() && Files.isRegularFile(path)) {
			try {
				response.write(path);
			} catch (IOException e) {
				response.setStatus(Response.Status.SERVERERROR);
			}
		}
		else {
			response.setStatus(Response.Status.NOTFOUND);
		}
	}
	
	@Override
	public void doPost(Request request, Response response) {
		String origin = request.getHeader(com.itahm.http.Connection.Header.ORIGIN.toString());
		
		if (origin != null) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			response.setHeader("Access-Control-Allow-Credentials", "true");
		}

		try { 
			JSONObject req = new JSONObject(new String(request.read(), StandardCharsets.UTF_8.name()));
			
			if (!req.has("command")) {
				throw new JSONException(KR.ERROR_CMD_NOT_FOUND);
			}
			
			Session session = request.getSession(false);
			
			switch (req.getString("command").toUpperCase()) {
				case "SIGNIN":
					if (session == null) {
						try (Connection c = connPool.getConnection()) {
							try (PreparedStatement pstmt = c.prepareStatement("SELECT username, level FROM account"+
								" WHERE username=? AND password=?;")) {
								pstmt.setString(1, req.getString("username"));
								pstmt.setString(2, req.getString("password"));
								
								try (ResultSet rs = pstmt.executeQuery()) {							
									if (rs.next()) {
										JSONObject account = new JSONObject()
											.put("username", rs.getString(1))
											.put("level", rs.getInt(2));
										
										session = request.getSession();
										
										session.setAttribute("account", account);
										
										session.setMaxInactiveInterval(SESS_TIMEOUT);
										
										response.write(account.toString());
									} else {
										response.setStatus(Response.Status.UNAUTHORIZED);
									}
								}
							}
						} catch (SQLException sqle) {
							sqle.printStackTrace();
							
							response.setStatus(Response.Status.SERVERERROR);
						}
					}
					else {
						response.write(((JSONObject)session.getAttribute("account")).toString());
					}
					
					break;
					
				case "SIGNOUT":
					if (session == null) {
						response.setStatus(Response.Status.UNAUTHORIZED);
					} else {
						session.invalidate();
					}
					
					break;
				default:
					this.services.forEach(service -> 
						service.service(req, response)
					);
			}
			
		}
		catch (JSONException | UnsupportedEncodingException e) {
			response.setStatus(Response.Status.BADREQUEST);
			
			response.write(new JSONObject().
				put("error", e.getMessage()).toString());
		}
	}
	
	public void close() {
		synchronized (this.isClosed) {
			if (this.isClosed) {
				return;
			}
			
			this.isClosed = true;
		}
		
		this.services.forEach(service -> {
			try {
				service.stop();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		try {
			super.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	public static void main(String[] args) throws Exception {
		Builder builder = new ITAhM.Builder();
		
		for (int i=0, _i=args.length; i<_i; i++) {
			if (args[i].indexOf("-") != 0) {
				continue;
			}
			
			switch(args[i].substring(1).toUpperCase()) {
			case "ROOT":
				builder.root(args[++i]);
				
				break;
			case "TCP":
				try {
					builder.tcp = Integer.parseInt(args[++i]);
				}
				catch (NumberFormatException nfe) {}
				
				break;
			}
		}
				
		ITAhM itahm = builder
			//.license("A402B93D8051")
			//.expire()
			.build();
		
		Runtime.getRuntime().addShutdownHook(
			new Thread() {
				
				@Override
				public void run() {
					if (itahm != null) {
						itahm.close();
					}
				}
			}
		);
	}
}
