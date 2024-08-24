package trader;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

public class JSONSender implements Runnable {
	private final String urlString;
	private final JSONObject jsonData;
	private final APICallback  callback;

	public JSONSender (APICallback callback, JSONObject jsonData) {
		this.urlString = "http://localhost:42784/gateway";
		this.jsonData = jsonData;
		this.callback = callback;
		System.out.println(jsonData.toString().length());
	}

	@Override
	public void run() {
		try {
			HttpClient httpClient = HttpClients.createDefault();
			HttpPost httpPost = new HttpPost(this.urlString);
			httpPost.setHeader("Content-Type", "application/json");

			String jsonPayload = jsonData.toString();

			StringEntity entity = new StringEntity(jsonPayload);
			httpPost.setEntity(entity);

			// Execute the request
			HttpResponse response = httpClient.execute(httpPost);

			String responseText = EntityUtils.toString(response.getEntity());

			int responseCode = response.getStatusLine().getStatusCode();
			if (responseCode != 200)
				callback.onAPIError(responseCode);
			else 
				callback.onAPISuccess(responseText);
		}
		catch (Exception e) {
			e.printStackTrace();
			callback.onAPIError(-1);
		}
		
	}
}
