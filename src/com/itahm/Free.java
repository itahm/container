package com.itahm;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.itahm.http.HTTPServer;
import com.itahm.http.Request;
import com.itahm.http.Response;
import com.itahm.json.JSONException;
import com.itahm.json.JSONObject;
import com.itahm.lang.KR;
import com.itahm.service.NMS;

public class Free extends HTTPServer {
	
	private final NMS nms;
	private Boolean isClosed = false;
	
	public Free() throws Exception {
		this("0.0.0.0", 2014);
	}
	
	public Free(String ip, int tcp) throws Exception {
		this(ip, tcp, Path.of(ITAhM.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent());
	}

	public Free(String ip, int tcp, Path path) throws Exception {
		super(ip, tcp);
		
		System.out.format("ITAhM HTTP Server started with TCP %d.\n", tcp);
		
		Path root = path.resolve("data");
		
		if (!Files.isDirectory(root)) {
			Files.createDirectories(root);
		}
		
		nms = new NMS(root);
		
		nms.start();
	}
	
	@Override
	public void doGet(Request request, Response response) {
		response.setStatus(Response.Status.NOTFOUND);
	}
	
	@Override
	public void doPost(Request request, Response response) {
		try {
			JSONObject data = new JSONObject(new String(request.read(), StandardCharsets.UTF_8.name()));
			
			if (!data.has("command")) {
				throw new JSONException(KR.ERROR_CMD_NOT_FOUND);
			}
			
			if (this.nms.service(request, response, data)) {
				return;
			} else {
				response.setStatus(Response.Status.UNAVAILABLE);
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
		
		
		this.nms.stop();
		
		try {
			super.close();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	public static void main(String[] args) throws Exception {		
		Free itahm = new Free();
		
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
