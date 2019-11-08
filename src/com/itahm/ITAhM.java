package com.itahm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.itahm.http.HTTPServer;
import com.itahm.http.Request;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.lang.KR;
import com.itahm.service.NMS;
import com.itahm.service.Serviceable;
import com.itahm.service.SignIn;

public class ITAhM extends HTTPServer {
	
	private final Path root;
	private Boolean isClosed = false;
	private final Map<String, Serviceable> services = new LinkedHashMap<>();
	
	public ITAhM() throws Exception {
		this("0.0.0.0", 2014);
	}
	
	public ITAhM(String ip, int tcp) throws Exception {
		this(ip, tcp, Path.of(ITAhM.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent());
	}

	public ITAhM(String ip, int tcp, Path path) throws Exception {
		super(ip, tcp);
		
		System.out.format("ITAhM HTTP Server started with TCP %d.\n", tcp);
		
		root = path.resolve("data");
		
		if (!Files.isDirectory(root)) {
			Files.createDirectories(root);
		}
		
		services.put("SIGNIN", new SignIn(root));
		services.put("NMS", new NMS.Builder(root)
			//.license()
			//.expire()
			.build());
		
		for (String name : this.services.keySet()) {
			this.services.get(name).start();
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
			JSONObject data = new JSONObject(new String(request.read(), StandardCharsets.UTF_8.name()));
			
			if (!data.has("command")) {
				throw new JSONException(KR.ERROR_CMD_NOT_FOUND);
			}
			
			Serviceable service;
			
			switch (data.getString("command").toUpperCase()) {
			case "SERVICE":
				JSONObject body = new JSONObject();
				
				for (String name : this.services.keySet()) {
					service = this.services.get(name);
			
					if (!name.equals("SIGNIN")) {
						body.put(name.toLowerCase(), service.isRunning());
					}
				}
				
				response.write(body.toString());
				
				return;
			case "START":				
				service = this.services.get(data.getString("service").toUpperCase());
				
				if (service == null) {
					// TODO
				} else {
					service.start();
				}
				
				return;
			case "STOP":
				service = this.services.get(data.getString("service").toUpperCase());
				
				if (service == null) {
					// TODO
				} else {
					service.stop();
				}
				
				return;
				
			default:
				for (String name : this.services.keySet()) {
					if (this.services.get(name).service(request, response, data)) {
						return;
					}
				}
				
				response.setStatus(Response.Status.UNAVAILABLE);
				
				return;
			}
		} catch (JSONException | UnsupportedEncodingException e) {
			response.write(new JSONObject()
				.put("error", e.getMessage())
				.toString());
		}
		
		response.setStatus(Response.Status.BADREQUEST);
	}
	
	public void close() {
		synchronized (this.isClosed) {
			if (this.isClosed) {
				return;
			}
			
			this.isClosed = true;
		}
		
		for (String name : this.services.keySet()) {
			this.services.get(name).stop();
		}
		
		try {
			super.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	public static void main(String[] args) throws Exception {
		Path root = null;
		String ip = "0.0.0.0";
		int tcp = 2014;
		
		for (int i=0, _i=args.length; i<_i; i++) {
			if (args[i].indexOf("-") != 0) {
				continue;
			}
			
			switch(args[i].substring(1).toUpperCase()) {
			case "ROOT":
				root = Path.of(args[++i]);
				
				break;
			case "TCP":
				try {
					tcp = Integer.parseInt(args[++i]);
				} catch (NumberFormatException nfe) {}
				
				break;
			}
		}
		
		ITAhM itahm = root == null? new ITAhM(ip, tcp): new ITAhM(ip, tcp, root);
		
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
