package trader;

public interface APICallback {
	public void onAPISuccess(String result);
	public void onAPIError(int responseCode);

}
