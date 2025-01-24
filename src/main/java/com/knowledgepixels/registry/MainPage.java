package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.getMaxValue;
import static com.knowledgepixels.registry.RegistryDB.getValue;
import static com.knowledgepixels.registry.RegistryDB.mongoSession;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

public class MainPage extends Page {

	public static void show(ServerRequest req, HttpServletResponse httpResp) throws IOException {
		MainPage obj = new MainPage(req, httpResp);
		obj.show();
	}

	private MainPage(ServerRequest req, HttpServletResponse httpResp) {
		super(req, httpResp);
	}

	protected void show() throws IOException {
		String format;
		String ext = getReq().getExtension();
		if ("json".equals(ext)) {
			format = "application/json";
		} else if (ext == null || "html".equals(ext)) {
			String suppFormats = "application/json,text/html";
			format = Utils.getMimeType(getHttpReq(), suppFormats);
		} else {
			getResp().sendError(400, "Invalid request: " + getReq().getFullRequest());
			return;
		}

		if (getReq().getPresentationFormat() != null) {
			getResp().setContentType(getReq().getPresentationFormat());
		} else {
			getResp().setContentType(format);
		}

		if ("application/json".equals(format)) {
			println(RegistryInfo.getLocal().asJson());
		} else {
			String status = getValue("serverInfo", "status").toString();
			printHtmlHeader("Nanopub Registry - alpha");
			println("<h1>Nanopub Registry - alpha</h1>");
			println("<h3>Formats</h3>");
			println("<p>");
			println("<a href=\".json\">.json</a> |");
			println("<a href=\".json.txt\">.json.txt</a>");
			println("</p>");
			println("<h3>Server</h3>");
			println("<ul>");
			println("<li><em>setupId:</em> " + getValue("serverInfo", "setupId") + "</li>");
			println("<li><em>coverageTypes:</em> " + getValue("serverInfo", "coverageTypes") + "</li>");
			println("<li><em>coverageAgents:</em> " + getValue("serverInfo", "coverageAgents") + "</li>");
			println("<li><em>status:</em> " + status + "</li>");
			println("<li><em>loadCounter:</em> " + getMaxValue("nanopubs", "counter") + "</li>");
			println("<li><em>trustStateCounter:</em> " + getValue("serverInfo", "trustStateCounter") + "</li>");
			Object lastTimeUpdate = getValue("serverInfo", "lastTrustStateUpdate");
			if (lastTimeUpdate != null) {
				println("<li><em>lastTrustStateUpdate:</em> " + lastTimeUpdate.toString().replaceFirst("\\.[^.]*$", "") + "</li>");
			} else {
				println("<li><em>lastTrustStateUpdate:</em> null</li>");
			}
			Object trustStateHash = getValue("serverInfo", "trustStateHash");
			if (trustStateHash != null) trustStateHash = trustStateHash.toString().substring(0, 10);
			println("<li><em>trustStateHash:</em> " + trustStateHash + "</li>");
			String oSetting = getValue("setting", "original").toString();
			println("<li><em>originalSetting:</em> <a href=\"/np/" + oSetting + "\"><code>" + oSetting.substring(0, 10) + "</code></a></li>");
			String cSetting = getValue("setting", "current").toString();
			println("<li><em>currentSetting:</em> <a href=\"/np/" + cSetting + "\"><code>" + cSetting.substring(0, 10) + "</code></a></li>");
			println("</ul>");

			println("<h3>Agents</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("agents").countDocuments(mongoSession) + "</p>");
				println("<p><a href=\"/agents\">&gt; agents</a></pi>");
			}

			println("<h3>Accounts</h3>");
			if (status.equals("loading")) {
				println("<p><em>(loading...)</em></p>");
			} else {
				println("<p>Count: " + collection("accounts").countDocuments(mongoSession) + "</p>");
				println("<p><a href=\"/list\">&gt; accounts</a></pi>");
			}

			println("<h3>Nanopubs</h3>");
			println("<p>Count: " + getMaxValue("nanopubs", "counter") + "</p>");
			println("<p><a href=\"/latestNanopubs\">&gt; latestNanopubs</a></pi>");
			printHtmlFooter();
		}
	}

}
