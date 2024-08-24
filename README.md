  ## Accessible Stock Trading Platform for Screenblinds and Visually Impaired

**Description:**

This Eclipse-based project aims to create an inclusive and user-friendly stock trading platform designed specifically for individuals with visual impairments. The platform incorporates advanced accessibility features, such as screen reader compatibility and intuitive navigation, to ensure seamless interaction for users with limited or no vision.

**Key Features:**

* **Screen Reader Compatibility:** Fully compatible with popular screen readers like JAWSVoiceOver,  and NVDA for accurate and efficient navigation.
* **Operating systems:** Tested on Windows and MacOS.
* **Intuitive Interface:** A visually uncluttered and accessible interface with clear labels, keyboard shortcuts, and audio cues.
* **Real-time Market Data:** Provides real-time updates on stock prices, market trends, and news.
* **Trading Functionality:** Enables users to place buy and sell orders with stop loss and take profit.

**Technologies:**

* Java
* Eclipse IDE
* Accessibility frameworks (e.g., JAWS, NVDA)

**Future Plans:**

* Integrate with financial data providers for more comprehensive market information.
* Explore additional accessibility features, such as voice commands or Braille displays.
* Expand the platform to include other financial instruments beyond stocks.

## **Disclaimer**

**Please note that this project is intended for educational and informational purposes only.** The developers of this platform do not assume any responsibility for financial losses incurred due to system malfunctions, bugs, or errors in judgment. It is crucial to thoroughly test the platform using a demo account before engaging in real-money trading. 

**Always exercise caution and conduct thorough research before making any investment decisions.**

## **Dukascopy API Integration**

This project utilizes the Dukascopy API to access real-time market data and potentially place trades (depending on implemented functionality). To leverage this feature, users will require a Dukascopy demo account.

**Steps for Configuration:**

1. **Create Dukascopy Demo Account:** Visit the Dukascopy website and register for a free demo account. This will provide you with login credentials for accessing their API.
2. **API Credentials Setup:** Locate the file `/vTrader/my_config/config.properties` within the project directory.  **Important
3. **Configure `config.properties`:** Open the `config.properties` file and update the following fields with your Dukascopy demo account credentials:
    
