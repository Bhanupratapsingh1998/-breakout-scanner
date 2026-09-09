package com.javawarriors.breakout.marketdata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static Nifty 50 + Nifty Next 50 constituents (NSE, .NS suffix).
 * NSE Indices rebalances these lists semi-annually (Mar/Sep) — review
 * and update against the official NSE index factsheet periodically.
 */
public final class NiftyUniverse {

    public static final List<String> NIFTY_50 = List.of(
            "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "ICICIBANK.NS", "INFY.NS",
            "BHARTIARTL.NS", "SBIN.NS", "LICI.NS", "ITC.NS", "HINDUNILVR.NS",
            "LT.NS", "BAJFINANCE.NS", "HCLTECH.NS", "KOTAKBANK.NS", "SUNPHARMA.NS",
            "MARUTI.NS", "M&M.NS", "AXISBANK.NS", "ULTRACEMCO.NS", "NTPC.NS",
            "ADANIENT.NS", "TITAN.NS", "ONGC.NS", "BAJAJFINSV.NS", "ASIANPAINT.NS",
            "WIPRO.NS", "ADANIPORTS.NS", "POWERGRID.NS", "COALINDIA.NS", "NESTLEIND.NS",
            "JSWSTEEL.NS", "BAJAJ-AUTO.NS", "TATAMOTORS.NS", "GRASIM.NS", "HDFCLIFE.NS",
            "TECHM.NS", "SBILIFE.NS", "INDUSINDBK.NS", "TATASTEEL.NS", "CIPLA.NS",
            "DRREDDY.NS", "EICHERMOT.NS", "APOLLOHOSP.NS", "BRITANNIA.NS", "DIVISLAB.NS",
            "HEROMOTOCO.NS", "HINDALCO.NS", "BPCL.NS", "SHRIRAMFIN.NS", "TATACONSUM.NS"
    );

    public static final List<String> NIFTY_NEXT_50 = List.of(
            "ADANIGREEN.NS", "ADANIPOWER.NS", "AMBUJACEM.NS", "BANKBARODA.NS", "BEL.NS",
            "BOSCHLTD.NS", "CANBK.NS", "CGPOWER.NS", "CHOLAFIN.NS", "COLPAL.NS",
            "DABUR.NS", "DLF.NS", "GAIL.NS", "GODREJCP.NS", "HAVELLS.NS",
            "HAL.NS", "ICICIGI.NS", "ICICIPRULI.NS", "IOC.NS", "IRFC.NS",
            "JINDALSTEL.NS", "JIOFIN.NS", "LODHA.NS", "LTIM.NS", "MARICO.NS",
            "MOTHERSON.NS", "NAUKRI.NS", "PIDILITIND.NS", "PFC.NS", "PNB.NS",
            "RECLTD.NS", "SIEMENS.NS", "SRF.NS", "TATAPOWER.NS", "TORNTPHARM.NS",
            "TVSMOTOR.NS", "UNITDSPR.NS", "VBL.NS", "VEDL.NS", "ZOMATO.NS",
            "ZYDUSLIFE.NS", "ABB.NS", "ATGL.NS", "BAJAJHLDNG.NS", "BERGEPAINT.NS",
            "DIXON.NS", "INDIGO.NS", "INDHOTEL.NS", "PERSISTENT.NS", "POLYCAB.NS"
    );

    public static final List<String> ALL;
    static {
        List<String> all = new ArrayList<>();
        all.addAll(NIFTY_50);
        all.addAll(NIFTY_NEXT_50);
        ALL = List.copyOf(all);
    }

    /** Ticker -> display company name, for UI labeling. */
    public static final Map<String, String> NAMES = Map.<String, String>ofEntries(
            Map.entry("RELIANCE.NS", "Reliance Industries"),
            Map.entry("TCS.NS", "Tata Consultancy Services"),
            Map.entry("HDFCBANK.NS", "HDFC Bank"),
            Map.entry("ICICIBANK.NS", "ICICI Bank"),
            Map.entry("INFY.NS", "Infosys"),
            Map.entry("BHARTIARTL.NS", "Bharti Airtel"),
            Map.entry("SBIN.NS", "State Bank of India"),
            Map.entry("LICI.NS", "Life Insurance Corporation of India"),
            Map.entry("ITC.NS", "ITC"),
            Map.entry("HINDUNILVR.NS", "Hindustan Unilever"),
            Map.entry("LT.NS", "Larsen & Toubro"),
            Map.entry("BAJFINANCE.NS", "Bajaj Finance"),
            Map.entry("HCLTECH.NS", "HCL Technologies"),
            Map.entry("KOTAKBANK.NS", "Kotak Mahindra Bank"),
            Map.entry("SUNPHARMA.NS", "Sun Pharmaceutical Industries"),
            Map.entry("MARUTI.NS", "Maruti Suzuki India"),
            Map.entry("M&M.NS", "Mahindra & Mahindra"),
            Map.entry("AXISBANK.NS", "Axis Bank"),
            Map.entry("ULTRACEMCO.NS", "UltraTech Cement"),
            Map.entry("NTPC.NS", "NTPC"),
            Map.entry("ADANIENT.NS", "Adani Enterprises"),
            Map.entry("TITAN.NS", "Titan Company"),
            Map.entry("ONGC.NS", "Oil & Natural Gas Corporation"),
            Map.entry("BAJAJFINSV.NS", "Bajaj Finserv"),
            Map.entry("ASIANPAINT.NS", "Asian Paints"),
            Map.entry("WIPRO.NS", "Wipro"),
            Map.entry("ADANIPORTS.NS", "Adani Ports & SEZ"),
            Map.entry("POWERGRID.NS", "Power Grid Corporation of India"),
            Map.entry("COALINDIA.NS", "Coal India"),
            Map.entry("NESTLEIND.NS", "Nestle India"),
            Map.entry("JSWSTEEL.NS", "JSW Steel"),
            Map.entry("BAJAJ-AUTO.NS", "Bajaj Auto"),
            Map.entry("TATAMOTORS.NS", "Tata Motors"),
            Map.entry("GRASIM.NS", "Grasim Industries"),
            Map.entry("HDFCLIFE.NS", "HDFC Life Insurance"),
            Map.entry("TECHM.NS", "Tech Mahindra"),
            Map.entry("SBILIFE.NS", "SBI Life Insurance"),
            Map.entry("INDUSINDBK.NS", "IndusInd Bank"),
            Map.entry("TATASTEEL.NS", "Tata Steel"),
            Map.entry("CIPLA.NS", "Cipla"),
            Map.entry("DRREDDY.NS", "Dr. Reddy's Laboratories"),
            Map.entry("EICHERMOT.NS", "Eicher Motors"),
            Map.entry("APOLLOHOSP.NS", "Apollo Hospitals Enterprise"),
            Map.entry("BRITANNIA.NS", "Britannia Industries"),
            Map.entry("DIVISLAB.NS", "Divi's Laboratories"),
            Map.entry("HEROMOTOCO.NS", "Hero MotoCorp"),
            Map.entry("HINDALCO.NS", "Hindalco Industries"),
            Map.entry("BPCL.NS", "Bharat Petroleum Corporation"),
            Map.entry("SHRIRAMFIN.NS", "Shriram Finance"),
            Map.entry("TATACONSUM.NS", "Tata Consumer Products"),
            Map.entry("ADANIGREEN.NS", "Adani Green Energy"),
            Map.entry("ADANIPOWER.NS", "Adani Power"),
            Map.entry("AMBUJACEM.NS", "Ambuja Cements"),
            Map.entry("BANKBARODA.NS", "Bank of Baroda"),
            Map.entry("BEL.NS", "Bharat Electronics"),
            Map.entry("BOSCHLTD.NS", "Bosch"),
            Map.entry("CANBK.NS", "Canara Bank"),
            Map.entry("CGPOWER.NS", "CG Power and Industrial Solutions"),
            Map.entry("CHOLAFIN.NS", "Cholamandalam Investment & Finance"),
            Map.entry("COLPAL.NS", "Colgate-Palmolive (India)"),
            Map.entry("DABUR.NS", "Dabur India"),
            Map.entry("DLF.NS", "DLF"),
            Map.entry("GAIL.NS", "GAIL (India)"),
            Map.entry("GODREJCP.NS", "Godrej Consumer Products"),
            Map.entry("HAVELLS.NS", "Havells India"),
            Map.entry("HAL.NS", "Hindustan Aeronautics"),
            Map.entry("ICICIGI.NS", "ICICI Lombard General Insurance"),
            Map.entry("ICICIPRULI.NS", "ICICI Prudential Life Insurance"),
            Map.entry("IOC.NS", "Indian Oil Corporation"),
            Map.entry("IRFC.NS", "Indian Railway Finance Corporation"),
            Map.entry("JINDALSTEL.NS", "Jindal Steel & Power"),
            Map.entry("JIOFIN.NS", "Jio Financial Services"),
            Map.entry("LODHA.NS", "Macrotech Developers (Lodha)"),
            Map.entry("LTIM.NS", "LTIMindtree"),
            Map.entry("MARICO.NS", "Marico"),
            Map.entry("MOTHERSON.NS", "Samvardhana Motherson International"),
            Map.entry("NAUKRI.NS", "Info Edge (India)"),
            Map.entry("PIDILITIND.NS", "Pidilite Industries"),
            Map.entry("PFC.NS", "Power Finance Corporation"),
            Map.entry("PNB.NS", "Punjab National Bank"),
            Map.entry("RECLTD.NS", "REC Limited"),
            Map.entry("SIEMENS.NS", "Siemens"),
            Map.entry("SRF.NS", "SRF"),
            Map.entry("TATAPOWER.NS", "Tata Power"),
            Map.entry("TORNTPHARM.NS", "Torrent Pharmaceuticals"),
            Map.entry("TVSMOTOR.NS", "TVS Motor Company"),
            Map.entry("UNITDSPR.NS", "United Spirits"),
            Map.entry("VBL.NS", "Varun Beverages"),
            Map.entry("VEDL.NS", "Vedanta"),
            Map.entry("ZOMATO.NS", "Eternal (Zomato)"),
            Map.entry("ZYDUSLIFE.NS", "Zydus Lifesciences"),
            Map.entry("ABB.NS", "ABB India"),
            Map.entry("ATGL.NS", "Adani Total Gas"),
            Map.entry("BAJAJHLDNG.NS", "Bajaj Holdings & Investment"),
            Map.entry("BERGEPAINT.NS", "Berger Paints India"),
            Map.entry("DIXON.NS", "Dixon Technologies (India)"),
            Map.entry("INDIGO.NS", "InterGlobe Aviation (IndiGo)"),
            Map.entry("INDHOTEL.NS", "Indian Hotels Company"),
            Map.entry("PERSISTENT.NS", "Persistent Systems"),
            Map.entry("POLYCAB.NS", "Polycab India")
    );

    private NiftyUniverse() {}
}
