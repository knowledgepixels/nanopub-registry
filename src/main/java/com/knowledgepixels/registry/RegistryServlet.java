package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.mongodb.client.model.Indexes.ascending;

import java.io.IOException;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RegistryServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	@Override
	public void init() throws ServletException {
		super.init();

		RegistryDB.init();

		new Thread(() -> {
			Task.runTasks();
		}).start();
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		try {
			setGeneralHeaders(resp);
			ServerRequest r = new ServerRequest(req);
			if (r.isEmpty()) {
				MainPage.show(r, resp);
			} else if (r.getRequestString().matches("/list(/.*)?")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/agents?")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/latestNanopubs")) {
				ListPage.show(r, resp);
			} else if (r.getRequestString().matches("/np(/.*)?")) {
				NanopubPage.show(r, resp);
			} else if (r.getRequestString().matches("/debug/trustPaths")) {
				MongoCursor<Document> tp = collection("trustPaths").find(mongoSession).sort(ascending("_id")).cursor();
				while (tp.hasNext()) {
					Document d = tp.next();
					resp.getOutputStream().println(d.get("_id") + " (" + d.get("type") + ")");
				}
				resp.setContentType("text/plain");
			} else if (r.getRequestString().matches("/debug/endorsements")) {
				MongoCursor<Document> tp = collection("endorsements").find(mongoSession).cursor();
				while (tp.hasNext()) {
					Document d = tp.next();
					resp.getOutputStream().println(d.get("agent") + ">" + d.get("pubkey") + " " + d.get("endorsedNanopub") + " " + d.get("source") + " (" + d.get("status") + ")");
				}
				resp.setContentType("text/plain");
			} else if (r.getRequestString().matches("/debug/accounts")) {
				MongoCursor<Document> tp = collection("accounts").find(mongoSession).cursor();
				while (tp.hasNext()) {
					Document d = tp.next();
					resp.getOutputStream().println(d.getString("agent") + ">" + d.get("pubkey") + " " + d.get("depth") + " (" + d.get("status") + ")");
				}
				resp.setContentType("text/plain");
			} else if (r.getFullRequest().equals("/style/plain.css")) {
				ResourcePage.show(r, resp, "style.css", "text/css");
			}
		} finally {
			resp.getOutputStream().close();
			req.getInputStream().close();
		}
	}

	private void setGeneralHeaders(HttpServletResponse resp) {
		resp.setHeader("Access-Control-Allow-Origin", "*");
	}

}