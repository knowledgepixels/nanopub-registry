package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;
import static com.mongodb.client.model.Indexes.ascending;

import java.io.IOException;

import org.bson.Document;

import com.mongodb.client.MongoCursor;

import jakarta.servlet.http.HttpServletResponse;

public class DebugPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		DebugPage obj = new DebugPage(req, httpResp);
		obj.show();
	}

	private DebugPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	protected void show() throws IOException {
		ServerRequest r = getReq();
		HttpServletResponse resp = getResp();

		if (r.getRequestString().matches("/debug/trustPaths")) {
			String counterString = r.getHttpRequest().getParameter("trustStateCounter");
			if (counterString == null) {
				resp.getOutputStream().print(getTrustPathsTxt());
			} else {
				Long counter = Long.parseLong(counterString);
				resp.getOutputStream().print(RegistryDB.getOne("debug_trustPaths", new Document("trustStateCounter", counter)).getString("trustStateTxt"));
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
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}
	}

	public static String getTrustPathsTxt() {
		String s = "";
		MongoCursor<Document> tp = collection("trustPaths").find(mongoSession).sort(ascending("_id")).cursor();
		while (tp.hasNext()) {
			Document d = tp.next();
			s += d.get("_id") + " (" + d.get("type") + ")\n";
		}
		return s;
	}

}
