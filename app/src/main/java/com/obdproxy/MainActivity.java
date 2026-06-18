package com.obdproxy;

import android.app.Activity;
import android.bluetooth.*;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import android.util.Log;

public class MainActivity extends Activity {
    private BluetoothAdapter bt;
    private BluetoothDevice elmDev;
    private BluetoothSocket elmSock, dkeSock;
    private java.net.Socket tcpSock; // TCP fallback for PC simulator
    private BluetoothServerSocket srvSock;
    private BufferedReader elmR, dkeR;
    private PrintWriter elmW, dkeW;
    private TextView statusView, logView, logPathView;
    private ScrollView logScroll; // for auto-scroll control
    private String currentLogPath = null; // current log file absolute path
    private ArrayAdapter<String> devAdapter;
    private List<BluetoothDevice> devList = new ArrayList<>();
    private Handler h = new Handler();
    private volatile boolean running = false;
    private volatile boolean pollRunning = false; // controls polling threads independently
    private volatile boolean simMode = false; // simulation when vLinker unavailable
    private volatile boolean staticSim = false; // static value simulation for calibration
    private volatile boolean obdFailed = false; // true when vLinker connect fails — refuse sim
    private volatile boolean firstPollLogged = false; // first OBD poll result logged
    private boolean paused = false; // pause polling (keep last values frozen)
    private final ConcurrentHashMap<String,String> lastRawHex = new ConcurrentHashMap<>(); // raw vLinker bytes per PID
    private final ConcurrentHashMap<String,String> lastDkeResp = new ConcurrentHashMap<>(); // last UDS 62 response per DID
    private String tcpAddr = null;  // TCP simulator IP / tunnel URL override
    private int tcpPort = 35000;
    private long runningSince = 0;
    private PrintWriter logFile = null; // file log for debug
    private PrintWriter dataLog = null; // OBD data CSV log
    private String dataLogPath = null; // path to data log file
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long CACHE_MS = 500;
    private static final long INTERP_MS = 200; // max interpolation window

    // ── UDS DID ↔ Mode 01 PID mapping (Mercedes + standard) ──
    // Format: "22XXXX" → "01YY"  (DID → PID)
    // Expected response bytes: many Mercedes DIDs expect 2+ byte responses
    private static final LinkedHashMap<String,String> U2O = new LinkedHashMap<>();
    static {
        // ── Mercedes-specific DIDs (from DKE binary analysis) ──
        // Verified HUD position mapping (2025-06-15)
        U2O.put("222000","010C"); // Pos6 RPM ← OBD RPM
        U2O.put("222011","0105"); // Pos3 Coolant °C ← OBD Coolant
        U2O.put("222029","0111"); // Pos4 Accel% ← OBD Throttle %
        U2O.put("222071","010F"); // Pos8 IAT (old DID)
        U2O.put("222014","010F"); // Pos8 IAT (new DID — DKE changed)
        U2O.put("222077","010B"); // Pos1 Boost PSI ← OBD MAP kPa
        U2O.put("225021","010D"); // Pos7 Speed km/h ← OBD Speed
        U2O.put("225024","010B"); // Boost alt
        U2O.put("226131","0105"); // Coolant alt
        U2O.put("22F190","0144"); // Pos? AFR ← Commanded Equivalence Ratio (was 0124→NO DATA)
        // Calculated/derived values
        U2O.put("226000","0104"); // Pos2 Torque Nm ← OBD Load %
        U2O.put("226040","010E"); // Pos5 Retard ← OBD Timing ° (old)
        U2O.put("22D010","010E"); // Pos5 Retard ← OBD Timing ° (newer DKE)
        // ── Standard UDS DIDs ──
        U2O.put("22F300","010C"); // RPM standard
        U2O.put("22F40D","010D"); // Speed standard
        U2O.put("22F405","0105"); // Coolant standard
        // ── Extra PIDs for diagnostics (DO NOT duplicate HUD DIDs above) ──
        U2O.put("222004","0104"); // Engine load
        U2O.put("22200A","010A"); // Fuel pressure
        U2O.put("22200B","010B"); // MAP
        U2O.put("22200E","010E"); // Timing
        U2O.put("22200F","010F"); // IAT
        U2O.put("222010","0110"); // MAF
        // NOTE: 222011 already mapped to 0105 (Coolant) above — do NOT remap to 010D
        U2O.put("22201F","011F"); // Run time
        U2O.put("22202F","012F"); // Fuel level
        U2O.put("222033","0133"); // Barometric pressure
        U2O.put("222046","0146"); // Ambient temp
        U2O.put("220100","0902"); // VIN query
    }
    // Expected minimum response bytes per DID (for padding)
    // All DIDs now use Mercedes 2-byte encoding (mercBytes)
    private static final HashMap<String,Integer> DID_MIN_BYTES = new HashMap<>();
    static {
        DID_MIN_BYTES.put("222000",2); // RPM (Pos6)
        DID_MIN_BYTES.put("222011",2); // Coolant °C (Pos3)
        DID_MIN_BYTES.put("222029",2); // Accel % (Pos4)
        DID_MIN_BYTES.put("222071",2); // IAT °C (Pos8)
        DID_MIN_BYTES.put("222077",2); // Boost PSI (Pos1)
        DID_MIN_BYTES.put("225021",2); // Speed km/h (Pos7)
        DID_MIN_BYTES.put("225024",2); // Boost alt
        DID_MIN_BYTES.put("22F190",2); // Timing advance
        DID_MIN_BYTES.put("226000",2); // Torque Nm (Pos2)
        DID_MIN_BYTES.put("226040",2); // Retard ° (Pos5, old)
        DID_MIN_BYTES.put("22D010",2); // Retard ° (Pos5, newer DKE)
        DID_MIN_BYTES.put("226131",2); // Coolant alt (Pos9)
    }
    // ── Dynamic composite DID tracking ──
    // DKE defines composites via 2C command; we parse and store sub-DID lists
    // Key = dynamic DID (like "22F300"), Value = list of "DIDhex:memSize" entries
    private final HashMap<String,java.util.ArrayList<String>> compositeDefs = new HashMap<>();
    private final java.util.LinkedHashMap<String,String> respCache = new java.util.LinkedHashMap<>();
    private final String[] FIXED_ORDER = {"222000","222011","222077","225021","226000","222029","222071","226131","226040","22D010"};
    
    // Parse 2C defineByIdentifier command and store sub-DID list with memSize
    // 2C format: 2C 01 [dynDID 2B] [repeat: sourceDID 2B + memSize 1B + pos 1B]
    // Each entry = 4 bytes (8 hex chars)
    void parse2C(String cmd){
        String u=cmd.toUpperCase().replaceAll("\\s+","");
        if(!u.startsWith("2C01")) return; // only handle defineByIdentifier
        String rest=u.substring(4); // after "2C01"
        if(rest.length()<4) return;
        String dynDid="22"+rest.substring(0,4); // dynamic DID like "22F300"
        String data=rest.substring(4); // sub-DID definitions
        
        java.util.ArrayList<String> subs=new java.util.ArrayList<>();
        // Each entry: sourceDID(4 hex chars) + memSize(2 hex chars) + pos(2 hex chars)
        // = 8 hex chars per entry, but sometimes pos is omitted on last entry
        int i=0;
        while(i+5<data.length()){ // at least DID(4) + memSize(2) = 6 chars
            String subDid="22"+data.substring(i,i+4); // source DID
            String memSizeHex=data.substring(i+4,i+6); // memSize byte
            int memSize=Integer.parseInt(memSizeHex,16);
            subs.add(subDid+":"+memSize); // store "222000:2"
            i+=8; // advance 8 hex chars = 4 bytes per full entry
            // If remaining is only 6 chars (DID 4 + memSize 2, no pos), handle last entry
            if(data.length()-i==6 && i+5<data.length()){
                String lastDid="22"+data.substring(i,i+4);
                String lastMem=data.substring(i+4,i+6);
                int lastSz=Integer.parseInt(lastMem,16);
                subs.add(lastDid+":"+lastSz);
                break;
            }
        }
        // Fallback: if no entries parsed, try old 2-byte-per-entry method
        if(subs.isEmpty()){
            i=0;
            while(i+3<=data.length()){
                String subDid="22"+data.substring(i,i+4);
                subs.add(subDid+":1"); // assume 1 byte
                i+=4;
            }
        }
        
        compositeDefs.put(dynDid, subs);
        Log.d("OBD","2C: dynamic "+dynDid+" = "+subs.size()+" subs: "+subs.toString());
    }
    
    // Build composite UDS 62 response using learned sub-DID list with memSize
    String compositeDyn(String did){
        java.util.ArrayList<String> subs=compositeDefs.get(did);
        if(subs==null||subs.isEmpty()){
            return "NO DATA\r>";
        }
        StringBuilder sb=new StringBuilder("62");
        // DID bytes from dynamic DID (e.g., "22F300" → "F3 00")
        if(did.length()>=6){sb.append(' ').append(did.charAt(2)).append(did.charAt(3)).append(' ').append(did.charAt(4)).append(did.charAt(5));}
        else sb.append(" F3 00");
        
        StringBuilder dbg=new StringBuilder(); // debug log
        for(String entry:subs){
            // Parse "DIDhex:memSize"
            String[] parts=entry.split(":");
            String subDid=parts[0];
            int memSize=parts.length>1?Integer.parseInt(parts[1]):1;
            
            String obd=U2O.get(subDid);
            if(obd==null){
                // Unmapped DID → pad with zeros
                for(int k=0;k<memSize;k++) sb.append(" 00");
                dbg.append(subDid).append(":NA ");
                continue;
            }
            PidCodec codec=CODEC.get(obd);
            PidPoint c=curr.get(obd);
            float val=(c!=null)?c.val:0;

            // Use DID-specific 2-byte encoding
            byte[] mb=mercBytes(subDid,val);
            if(mb==null||mb.length==0){
                for(int k=0;k<memSize;k++) sb.append(" 00");
                dbg.append(String.format("%s=%.0f:NOMERC ",subDid,val));
                continue;
            }
            
            // Encode according to memSize (truncate or pad Mercedes bytes)
            for(int k=0;k<memSize;k++){
                if(k<mb.length) sb.append(' ').append(String.format("%02X",mb[k]&0xFF));
                else sb.append(" 00");
            }
            dbg.append(String.format("%s=%.0f(%dB) ",subDid,val,memSize));
        }
        Log.d("OBD","CMP "+did+" memSizes="+dbg.toString().trim()+" => "+sb.toString());
        return sb.toString()+"\r>";
    }
    private static final HashMap<String,String> O2U = new HashMap<>();
    static { for(Map.Entry<String,String> e:U2O.entrySet())O2U.put(e.getValue(),e.getKey()); }
    // 010C(RPM)/010D(Speed)/0111(Throttle) prioritized for DKE normal polling
    private static final String[] PIDS = {"010C","010D","0111","010C","010D","0111","010C","010D","0111","010C","0105","010B","010F","010E","0104","0144"};
    // ── Manual calibration overrides: DID → desired display value ──
    // mercBytes applies inverse formula to convert to BE bytes
    private final ConcurrentHashMap<String,Integer> calOverride = new ConcurrentHashMap<>();
    // Command aliases: short name → DID (not PID! each DID=unique HUD position)
    private static final java.util.Map<String,String> CAL_CMD = new java.util.HashMap<>();
    static {
        CAL_CMD.put("R","222000"); // RPM (pos6)
        CAL_CMD.put("T","226000"); // Torque Nm (pos2)
        CAL_CMD.put("S","222011"); // Coolant °C (pos3)
        CAL_CMD.put("C","222029"); // Accel% (pos4)
        CAL_CMD.put("B","225021"); // Speed km/h (pos7)
        CAL_CMD.put("E","226040"); // Ignition Retard ° (pos5)
        CAL_CMD.put("H","222077"); // Boost PSI (pos1)
        CAL_CMD.put("I","222014"); // IAT (pos8, new DID)
        CAL_CMD.put("A","22F190"); // AFR (was collided with E)
        CAL_CMD.put("F","220100"); // Fuel/VIN (unused)
        CAL_CMD.put("L","226000"); // alias for Torque
    }

    // ── PID encoding/decoding rules ──
    interface PidCodec { float decode(byte[] b); byte[] encode(float v); }
    private static final HashMap<String,PidCodec> CODEC = new HashMap<>();
    static {
        CODEC.put("010C", new PidCodec(){ // RPM: (A*256+B)/4
            public float decode(byte[] b){return b.length>=2?(float)(((b[0]&0xFF)<<8)|(b[1]&0xFF))/4f:0;}
            public byte[] encode(float v){int i=Math.round(v*4);return new byte[]{(byte)(i>>8),(byte)(i&0xFF)};}});
        CODEC.put("010D", new PidCodec(){ // Speed: A km/h
            public float decode(byte[] b){return b.length>=1?b[0]&0xFF:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v),0,255)};}});
        CODEC.put("0105", new PidCodec(){ // Coolant: A-40 °C
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)-40:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v+40),0,255)};}});
        CODEC.put("010B", new PidCodec(){ // MAP: A kPa
            public float decode(byte[] b){return b.length>=1?b[0]&0xFF:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v),0,255)};}});
        CODEC.put("0111", new PidCodec(){ // Throttle: A*100/255 %
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)*100f/255f:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v*255f/100f),0,255)};}});
        CODEC.put("010F", new PidCodec(){ // IAT: A-40 °C
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)-40:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v+40),0,255)};}});
        CODEC.put("010E", new PidCodec(){ // Timing: A/2-64 °
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)/2f-64f:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round((v+64)*2),0,255)};}});
        CODEC.put("0104", new PidCodec(){ // Load: A*100/255 %
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)*100f/255f:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v*255f/100f),0,255)};}});
        CODEC.put("010A", new PidCodec(){ // Fuel pressure: A*3 kPa
            public float decode(byte[] b){return b.length>=1?(b[0]&0xFF)*3f:0;}
            public byte[] encode(float v){return new byte[]{(byte)clamp(Math.round(v/3f),0,255)};}});
        CODEC.put("0122", new PidCodec(){ // Fuel Rail Pressure: ((A*256)+B)*10 kPa
            public float decode(byte[] b){return b.length>=2?(float)(((b[0]&0xFF)<<8)|(b[1]&0xFF))*10f:0;}
            public byte[] encode(float v){int i=Math.round(v/10f);return new byte[]{(byte)(i>>8),(byte)(i&0xFF)};}});
        CODEC.put("0144", new PidCodec(){ // Commanded Equiv Ratio → AFR = 14.7 / ((A*256+B)*0.0000305)
            public float decode(byte[] b){float eq=b.length>=2?(((b[0]&0xFF)<<8)|(b[1]&0xFF))*0.0000305f:1;return eq>0.001f?14.7f/eq:14.7f;}
            public byte[] encode(float afr){if(afr<=0)return new byte[]{0,0};int i=Math.round(14.7f/afr/0.0000305f);return new byte[]{(byte)(i>>8),(byte)(i&0xFF)};}});
        CODEC.put("0124", new PidCodec(){ // O2 Equivalence Ratio: (A*256+B)*0.0000305 → AFR=14.7/ratio
            public float decode(byte[] b){return b.length>=2?14.7f/(((b[0]&0xFF)<<8|(b[1]&0xFF))*0.0000305f):0;}
            public byte[] encode(float v){if(v<=0)return new byte[]{0,0}; int i=Math.round(14.7f/v/0.0000305f); return new byte[]{(byte)(i>>8),(byte)(i&0xFF)};}});
        CODEC.put("0902", null); // VIN — no interpolation
    }

    // ── Smooth interpolation history ──
    static class PidPoint { float val; long ts; PidPoint(float v,long t){val=v;ts=t;} }
    private final ConcurrentHashMap<String,PidPoint> prev = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,PidPoint> curr = new ConcurrentHashMap<>();
    private final Object interpLock = new Object();

    // ── UI boilerplate ──
    @Override public void onCreate(Bundle s){super.onCreate(s);setContentView(R.layout.main);
        statusView=(TextView)findViewById(R.id.status);logView=(TextView)findViewById(R.id.log);
        logPathView=(TextView)findViewById(R.id.logPath);
        logScroll=(ScrollView)findViewById(R.id.logScroll);
        bt=BluetoothAdapter.getDefaultAdapter();Spinner sp=(Spinner)findViewById(R.id.deviceList);
        devAdapter=new ArrayAdapter<String>(this,R.layout.spinner_item);sp.setAdapter(devAdapter);
        findViewById(R.id.btnScan).setOnClickListener(new View.OnClickListener(){public void onClick(View v){scan();}});
        findViewById(R.id.btnStart).setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(running)stop();else start();}});
        // Simulation buttons — separate from device selection
        findViewById(R.id.btnStaticSim).setOnClickListener(new View.OnClickListener(){public void onClick(View v){startStaticSim();}});
        findViewById(R.id.btnDynamicSim).setOnClickListener(new View.OnClickListener(){public void onClick(View v){startDynamicSim();}});
        // Pause/Resume button
        final Button btnPause=(Button)findViewById(R.id.btnPause);
        btnPause.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(!running){log("Not running — nothing to pause");return;}
            paused=!paused;
            if(paused){
                pollRunning=false;btnPause.setText("▶ 继续");
                log("⏸ Polling PAUSED — values frozen");
                setStatus("⏸ PAUSED");
            }else{
                btnPause.setText("⏸ 暂停");
                log("▶ Polling RESUMED");
                startPollingThread();
            }
        }});
        // Log button: copy path to clipboard
        findViewById(R.id.btnLog).setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            if(currentLogPath!=null){
                android.content.ClipboardManager cm=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("logPath",currentLogPath));
                log("📋 已复制: "+currentLogPath);
            }else{
                log("尚未启动代理，无日志文件");
            }
        }});
        // Calibration SET button
        final android.widget.EditText cmdInput=(android.widget.EditText)findViewById(R.id.cmdInput);
        findViewById(R.id.btnSet).setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            String raw=cmdInput.getText().toString().trim();
            String cmd=raw.toUpperCase();
            log("CMD raw=["+raw+"] upper=["+cmd+"]");
            if(cmd.isEmpty()){log("Usage: R=8000 T=960 S=90 C=55 B=88 E=10 H=1000 I=30 A=1470  or  0=clear");return;}
            if(cmd.equals("0")){calOverride.clear();log("Calibration cleared");return;}
            if(cmd.equals("MAP")){
                calOverride.clear();
                calOverride.put("222077",1000); // Boost PSI pos1
                calOverride.put("226000",2000); // Torque Nm pos2
                calOverride.put("222011",3000); // Coolant °C pos3
                calOverride.put("222029",4000); // Accel% pos4
                calOverride.put("226040",5000); // Retard ° pos5
                calOverride.put("222000",6000); // RPM pos6
                calOverride.put("225021",7000); // Speed km/h pos7
                calOverride.put("222071",8000); // IAT pos8
                calOverride.put("226131",9000); // pos9
                log("MAP: all DIDs set 1000-9000. Tell me HUD values!");
                return;
            }
            String[] parts=cmd.split("[= ]");
            log("Parts: "+java.util.Arrays.toString(parts));
            if(parts.length>=2){
                String key=parts[0]; String did=CAL_CMD.get(key);
                if(did!=null){
                    try{int val=Integer.parseInt(parts[1]);calOverride.put(did,val);
                        log("CAL OK: "+key+" → DID="+did+" val="+val);}
                    catch(Exception ex){log("Bad number: "+parts[1]);}
                }else{log("Unknown key: "+key+". Use S/C/B/T/R/I/E/F/L");}
            }else{log("Format: S=1234");}
        }});
    }

    void scan(){devList.clear();devAdapter.clear();for(BluetoothDevice d:bt.getBondedDevices()){devList.add(d);devAdapter.add(d.getName()+"\n"+d.getAddress());}
        devAdapter.notifyDataSetChanged();log("Found "+devList.size()+" paired devices");
        logConnData("SCAN","Found "+devList.size()+" paired: "+java.util.Arrays.toString(devList.toArray()).replaceAll("[\\[\\]]",""));}
    void start(){Spinner sp=(Spinner)findViewById(R.id.deviceList);int p=sp.getSelectedItemPosition();
        if(p<0||devList.isEmpty()){log("Select a device first (or use Sim buttons)");return;}
        staticSim=false;simMode=false;tcpAddr=null;elmDev=devList.get(p);obdFailed=false;firstPollLogged=false;
        log("Target: "+elmDev.getName()+" ("+elmDev.getAddress()+")");
        logConnData("DEVICE",elmDev.getName()+" ("+elmDev.getAddress()+")");
        new Thread(new Runnable(){public void run(){runProxy();}}).start();}
    void startStaticSim(){
        if(dkeSock!=null&&dkeSock.isConnected()&&running){switchSim(true,false);return;}
        fullRestart(true,false);}
    void startDynamicSim(){
        if(dkeSock!=null&&dkeSock.isConnected()&&running){switchSim(false,true);return;}
        fullRestart(false,true);}
    void startOBD(){
        if(dkeSock!=null&&dkeSock.isConnected()&&running){switchSim(false,false);return;}
        fullRestart(false,false);}
    // Hot-switch simulation mode without disconnecting DKE
    void switchSim(boolean st, boolean sm){
        pollRunning=false;try{Thread.sleep(200);}catch(Exception e){}
        staticSim=st;simMode=sm;obdFailed=false;
        synchronized(interpLock){curr.clear();prev.clear();}
        log("Switched to "+(st?"Static":sm?"Dynamic":"Real OBD")+" mode (DKE stays connected)");
        startPollingThread();}
    // Full restart: close all, start fresh
    void fullRestart(boolean st, boolean sm){
        if(running){running=false;cleanup();try{Thread.sleep(500);}catch(Exception e){}}
        running=false;staticSim=st;simMode=sm;tcpAddr=null;obdFailed=false;firstPollLogged=false;
        log("Starting "+(st?"Static":sm?"Dynamic":"Real OBD")+" mode");
        new Thread(new Runnable(){public void run(){runProxy();}}).start();}
    void stop(){running=false;pollRunning=false;cleanup();log("Stopped");}

    // ═══════════════════════════════════════════════════════
    // 主代理循环 - 先开SPP服务器让DKE能发现，再连vLinker
    // ═══════════════════════════════════════════════════════
    void runProxy(){try{
        initLogFile();
        running=true;
        if(!staticSim&&!simMode&&tcpAddr==null){initDataLogReal();}
        else if(!staticSim&&!simMode&&tcpAddr!=null){initDataLogReal();}
        else{initDataLogSim();}

        // 1. Make Bluetooth discoverable so DKE can find us
        log("Making discoverable...");
        try{
            Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Thread.sleep(1500);
        }catch(Exception e){
            log("Discoverable skip: "+e.getMessage());
        }

        // 2. Start SPP server
        log("Starting SPP server as OBDLink MX...");
        srvSock=bt.listenUsingRfcommWithServiceRecord("OBDLink MX",SPP);
        log("SPP server ready - waiting DKE...");
        setStatus("Waiting DKE to connect...");

        while(running){
        if(!running)break;

        // 3. Accept DKE connection FIRST (blocking)
        try{dkeSock=srvSock.accept();}catch(Exception e){log("SPP accept closed");break;}
        dkeW=new PrintWriter(dkeSock.getOutputStream(),true);
        dkeR=new BufferedReader(new InputStreamReader(dkeSock.getInputStream()));
        log("DKE connected! Now connecting to ELM327...");

        // 4. Connect to vLinker AFTER DKE is ready
        if(!staticSim&&!simMode&&tcpAddr==null){connectVlinker();}
        else if(!staticSim&&!simMode&&tcpAddr!=null){connectTcp();}

        // 5. Start polling and serving
        runningSince=System.currentTimeMillis();
        startPollingThread();
        long rc=0;String line;
        while((line=dkeR.readLine())!=null){line=line.trim();if(line.isEmpty())continue;rc++;String r=proc(line);if(r!=null){dkeW.print(r);dkeW.flush();}
        if(rc%50==0){float up=(System.currentTimeMillis()-runningSince)/1000f;final String st=String.format("%dreqs %.0fs %.0fHz",rc,up,rc/up);h.post(new Runnable(){public void run(){setStatus(st);}});}}
        log("DKE disconnected ("+rc+" reqs)");
        pollRunning=false;try{dkeSock.close();}catch(Exception e){}
        }
    }catch(Exception e){final String er="Error: "+e.getMessage();h.post(new Runnable(){public void run(){log(er);}});}finally{cleanup();}}

    // ═══════════════════════════════════════════════════════
    // 后台轮询 + 插值历史更新 (REAL OBD)
    // ═══════════════════════════════════════════════════════
    void pollLoop(){long c=0,t0=System.currentTimeMillis();int stopped=0,timeout=0,nodata=0;
        while(pollRunning){for(String pid:PIDS){if(!pollRunning)break;
        String r=raw(pid,80); // 80ms timeout (avg vLinker resp ~86ms)
        if(r==null){timeout++;}
        else if(r.contains("STOPPED")){stopped++;}
        else if(r.contains("NO DATA")){nodata++;}
        if(c<10&&r!=null)log("POLL raw["+c+"] "+pid+" → ["+r.replace("\r","\\r")+"]");
        if(r!=null&&!r.contains("NO DATA")&&!r.contains("?")&&!r.contains("SEARCHING")&&!r.contains("STOPPED")){
            if(!firstPollLogged){
                firstPollLogged=true;
                logConnData("FIRST_POLL_OK","First valid OBD response: "+pid+" = ["+r.replace("\r","\\r")+"]");
                log("✅ First OBD poll OK: "+pid+" = ["+r.replace("\r","\\r")+"]");
            }
            PidCodec codec=CODEC.get(pid);
            if(codec!=null){
                byte[] data=extractData(pid,r);
                if(data!=null){
                    float val=codec.decode(data);
                    logPollData(pid,val,"real");
                    long now=System.currentTimeMillis();
                    synchronized(interpLock){
                        PidPoint oldCurr=curr.get(pid);
                        if(oldCurr!=null){prev.put(pid,oldCurr);}
                        curr.put(pid,new PidPoint(val,now));
                    }
                    String rawHex = "";
                    if(r!=null){
                        String[] rp=r.trim().split("\\s+");
                        StringBuilder hexb=new StringBuilder();
                        boolean after41=false;
                        for(String s:rp){
                            if(s.equals("41")||s.equals("61")){after41=true;continue;}
                            if(after41&&s.length()>=2&&s.matches("[0-9A-Fa-f]+")){
                                if(hexb.length()>0||!s.equalsIgnoreCase(pid.substring(2))){
                                    if(hexb.length()>0)hexb.append(' ');
                                    hexb.append(s.toUpperCase());
                                }
                            }
                        }
                        rawHex=hexb.toString();
                        lastRawHex.put(pid, rawHex);
                    }
                }
            } else {curr.put(pid,new PidPoint(0,System.currentTimeMillis()));}
        }c++; try{Thread.sleep(5);}catch(Exception e){} } // 5ms gap for vLinker UART
        if(!firstPollLogged && !simMode && c>=45){
            firstPollLogged=true;
            logConnData("FIRST_POLL_FAIL","No valid OBD response after "+c+" attempts");
            log("⛔ No valid OBD response after "+c+" attempts");
        }
        if(c%200==0){float s=(System.currentTimeMillis()-t0)/1000f;int err=stopped+timeout+nodata;
            PidPoint rC=curr.get("010C"), sC=curr.get("010D"), tC=curr.get("0111");
            final String p=String.format("Poll:%d/%.0fs=%.0fHz err:%d(S:%d T:%d N:%d) RPM:%.0f Spd:%.0f Thr:%.0f%%",
                c,s,c/s,err,stopped,timeout,nodata,
                rC!=null?rC.val:-1, sC!=null?sC.val:-1, tC!=null?tC.val:-1);
            h.post(new Runnable(){public void run(){log(p);}});}}}

    // ═══════════════════════════════════════════════════════
    // 模拟轮询 — 无vLinker时生成假数据（用于测试蓝牙桥接）
    // ═══════════════════════════════════════════════════════
    void simPollLoop(){long c=0,t0=System.currentTimeMillis();
        while(pollRunning){
            long t=System.currentTimeMillis();
            double phase=t/8000.0*Math.PI*2;  // 8-second base cycle
            double phase2=t/11000.0*Math.PI*2; // 11-second for variety
            double phase3=t/6000.0*Math.PI*2;  // 6-second
            for(String pid:PIDS){
                if(!pollRunning)break;
                float val;
                switch(pid){
                    case "010C": val=(float)(750+4500*(Math.sin(phase2)+1)/2); break;          // RPM: 750~5250
                    case "010D": val=(float)(10+110*(Math.sin(phase3*0.7)+1)/2); break;         // Speed: 10~120 km/h
                    case "0105": val=(float)(85+25*Math.sin(phase*0.5)); break;                 // Coolant: 60~110°C
                    case "010B": val=(float)(Math.max(100,100+120*Math.sin(phase*0.8))); break; // MAP: 100~220 kPa
                    case "0111": val=(float)(15+80*(Math.sin(phase*0.6+1)+1)/2); break;        // Throttle: 15~95%
                    case "010F": val=(float)(25+20*Math.sin(phase*0.3+2)); break;               // IAT: 5~45°C
                    case "010E": val=(float)(15+30*Math.sin(phase2*0.7)); break;                // Timing: -15~45°
                    case "0104": val=(float)(30+65*(Math.sin(phase3*0.9)+1)/2); break;         // Load: 30~95%
                    case "0122": val=(float)(10000+25000*(Math.sin(phase*0.6)+1)/2); break;      // Fuel Rail: 10000~35000 kPa
                    case "0144": val=(float)(14.0+1.5*(Math.sin(phase*0.3)+1)/2); break;        // AFR: 14.0~15.5
                    case "0124": val=(float)(1470+20*Math.sin(phase*0.2)); break;               // AFR: ~14.5~14.9 (×100)
                    default: val=0;
                }
                if(pid.equals("010D")&&val<0)val=0;
                logPollData(pid,val,"sim");  // log dynamic sim value
                long now=System.currentTimeMillis();
                synchronized(interpLock){
                    PidPoint oldCurr=curr.get(pid);
                    if(oldCurr!=null){prev.put(pid,oldCurr);}
                    curr.put(pid,new PidPoint(val,now));
                }
                c++;
            }
            try{Thread.sleep(30);}catch(Exception e){}
            if(c%200==0){float s=(System.currentTimeMillis()-t0)/1000f;final String p=String.format("Sim:%d/%.0fs=%.0fHz",c,s,c/s);h.post(new Runnable(){public void run(){log(p);}});}
        }
    }

    // ═══════════════════════════════════════════════════════
    // vLinker connection + AT init (called BEFORE DKE connects for speed)
    // ═══════════════════════════════════════════════════════
    void connectVlinker(){
        try{
            log("Connecting to "+elmDev.getName()+" ("+elmDev.getAddress()+")...");
            logConnData("SOCKET","Creating RFCOMM socket to "+elmDev.getAddress());
            try{elmSock=elmDev.createInsecureRfcommSocketToServiceRecord(SPP);
                logConnData("SOCKET","Using createInsecureRfcommSocketToServiceRecord");}
            catch(Exception e){elmSock=elmDev.createRfcommSocketToServiceRecord(SPP);
                logConnData("SOCKET","Fallback to createRfcommSocketToServiceRecord");}
            logConnData("CONNECT","Calling elmSock.connect()...");
            elmSock.connect();
            logConnData("CONNECT","OK — socket connected");
            elmW=new PrintWriter(elmSock.getOutputStream(),true);
            elmR=new BufferedReader(new InputStreamReader(elmSock.getInputStream()));
            logConnData("STREAMS","I/O streams created");
            log("vLinker socket connected, waking up...");
            elmW.print("\r");elmW.flush();
            Thread.sleep(300);
            logConnData("WAKE","Sent \\r (wake-up)");
            String wake=readResp(500);
            log("Wake resp: ["+(wake!=null?wake.replace("\r","\\r"):"NULL")+"]");
            logConnData("WAKE_RESP",wake!=null?wake.replace("\r","\\r"):"NULL (no response)");
            log("Flushing stale data...");
            elmW.print("\r\r\r");elmW.flush();Thread.sleep(200);
            readResp(300);
            logConnData("FLUSH","Sent 3x \\r to flush");
            log("Sending ATZ...");
            logConnData("ATZ","Sending ATZ...");
            String atz=raw("ATZ",2000);
            log("ATZ → ["+(atz!=null?atz.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATZ_RESP",atz!=null?atz.replace("\r","\\r"):"NULL");
            if(atz==null||!atz.toUpperCase().contains("ELM")){
                log("⚠️ ATZ no ELM response, trying ATWS...");
                logConnData("ATWS","Sending ATWS...");
                String atws=raw("ATWS",1500);
                log("ATWS → ["+(atws!=null?atws.replace("\r","\\r"):"NULL")+"]");
                logConnData("ATWS_RESP",atws!=null?atws.replace("\r","\\r"):"NULL");
                log("Trying ATI...");
                logConnData("ATI","Sending ATI...");
                String ati=raw("ATI",1500);
                log("ATI → ["+(ati!=null?ati.replace("\r","\\r"):"NULL")+"]");
                logConnData("ATI_RESP",ati!=null?ati.replace("\r","\\r"):"NULL");
            }
            // Minimal AT init matching successful baseline (1700)
            logConnData("ATE0","Sending ATE0...");
            String ate0=raw("ATE0",300);
            log("ATE0 → ["+(ate0!=null?ate0.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATE0_RESP",ate0!=null?ate0.replace("\r","\\r"):"NULL");
            
            logConnData("ATH1","Sending ATH1...");
            String ath1=raw("ATH1",300);
            log("ATH1 → ["+(ath1!=null?ath1.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATH1_RESP",ath1!=null?ath1.replace("\r","\\r"):"NULL");
            
            logConnData("ATSP6","Sending ATSP6 (ISO 15765-4 CAN 11/500)...");
            String atsp6=raw("ATSP6",500);
            log("ATSP6 → ["+(atsp6!=null?atsp6.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATSP6_RESP",atsp6!=null?atsp6.replace("\r","\\r"):"NULL");
            
            raw("ATM0",200); // memory off
            logConnData("ATAT2","Sending ATAT2 (match DKE STBCOF)...");
            String atat2=raw("ATAT2",300);
            log("ATAT2 → ["+(atat2!=null?atat2.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATAT2_RESP",atat2!=null?atat2.replace("\r","\\r"):"NULL");
            
            Thread.sleep(800);
            String atdp=raw("ATDP",300);
            log("ATDP → ["+(atdp!=null?atdp.replace("\r","\\r"):"NULL")+"]");
            logConnData("ATDP_RESP",atdp!=null?atdp.replace("\r","\\r"):"NULL");
            elmW.print("\r");elmW.flush();Thread.sleep(100);readResp(200);
            
            // Warmup: send 0100 to trigger CAN controller init
            logConnData("WARMUP","Sending 0100 to wake CAN...");
            String warm=raw("0100",1500);
            log("Warmup 0100 → ["+(warm!=null?warm.replace("\r","\\r"):"NULL")+"]");
            logConnData("WARMUP_RESP",warm!=null?warm.replace("\r","\\r"):"NULL");
            Thread.sleep(300);
            
            log("ELM327 ready — protocol ISO 15765-4 CAN 11/500");
            logConnData("READY","ELM327 init complete — ready for DKE");
            simMode=false;
        }catch(Exception e){
            log("❌ vLinker FAILED: "+e.getClass().getSimpleName()+": "+e.getMessage());
            log("⛔ vLinker required — proxy will NOT use simulation!");
            logConnData("FAIL",e.getClass().getSimpleName()+": "+e.getMessage());
            obdFailed=true;simMode=false;
        }
    }
    void connectTcp(){
        try{
            log("Connecting TCP to "+tcpAddr+":"+tcpPort+"...");
            logConnData("TCP","Connecting to "+tcpAddr+":"+tcpPort);
            tcpSock=new java.net.Socket(tcpAddr,tcpPort);
            tcpSock.setSoTimeout(2000);
            elmW=new PrintWriter(tcpSock.getOutputStream(),true);
            elmR=new BufferedReader(new InputStreamReader(tcpSock.getInputStream()));
            logConnData("TCP","Socket connected");
            log("TCP connected to ELM327 simulator");
            raw("ATZ",600);raw("ATE0",100);raw("ATH0",100);raw("ATL0",100);raw("ATSP6",200);
            log("ELM327 TCP ready");
            simMode=false;
        }catch(Exception e){
            log("TCP failed: "+e.getMessage()+" - SIMULATION MODE");
            simMode=true;
        }
    }

    // ═══════════════════════════════════════════════════════
    // 静态数值模拟 — 固定值用于校准 DKE HUD 显示
    // ═══════════════════════════════════════════════════════
    void staticSimLoop(){long c=0,t0=System.currentTimeMillis();
        // Clear interpolation history to avoid stale values
        synchronized(interpLock){curr.clear();prev.clear();}
        // ── 固定模拟值 (方便对比 HUD 显示) ──
        final java.util.LinkedHashMap<String,Float> STATIC = new java.util.LinkedHashMap<>();
        STATIC.put("010C",2000f); // RPM
        STATIC.put("010D",88f);   // Speed km/h
        STATIC.put("0105",90f);   // Coolant °C
        STATIC.put("010B",150f);  // MAP kPa (≈0.5 bar boost)
        STATIC.put("0111",55f);   // Throttle %
        STATIC.put("010F",30f);   // IAT °C
        STATIC.put("010E",20f);   // Timing °
        STATIC.put("0104",60f);   // Load %
        STATIC.put("0122",20000f); // Fuel rail pressure kPa (20 MPa = 20000 kPa)
        STATIC.put("0144",14.7f);  // Commanded AFR
        STATIC.put("0124",1470f); // AFR ×100 (14.70)
        // Pre-populate curr/prev so DKE gets fixed values from first request
        long preNow=System.currentTimeMillis();
        synchronized(interpLock){
            for(java.util.Map.Entry<String,Float> e:STATIC.entrySet()){
                String pid=e.getKey(); float val=e.getValue();
                curr.put(pid,new PidPoint(val,preNow));
                prev.put(pid,new PidPoint(val,preNow));
            }
        }
        while(pollRunning){
            long now=System.currentTimeMillis();
            for(java.util.Map.Entry<String,Float> e:STATIC.entrySet()){
                if(!pollRunning)break;
                String pid=e.getKey();
                float val=e.getValue();
                logPollData(pid,val,"static");
                // Set both prev and curr to same value so interpolation returns constant
                synchronized(interpLock){
                    curr.put(pid,new PidPoint(val,now));
                    prev.put(pid,new PidPoint(val,now));
                }
                c++;
            }
            try{Thread.sleep(30);}catch(Exception ex){}
            if(c%200==0){float s=(System.currentTimeMillis()-t0)/1000f;final String p=String.format("Static:%d/%.0fs=%.0fHz",c,s,c/s);h.post(new Runnable(){public void run(){log(p);}});}
        }
    }

    // ═══════════════════════════════════════════════════════
    // Start/restart polling thread based on current mode flags
    // ═══════════════════════════════════════════════════════
    void startPollingThread(){
        final boolean st=staticSim, sm=simMode, no=obdFailed;
        final String label;
        if(no){label="⛔ NO OBD";setStatus("⛔ vLinker FAILED");}
        else if(st){label="Static sim (FIXED)";setStatus("Running ~static");initDataLogSim();}
        else if(sm){label="Dynamic sim (FAKE)";setStatus("⚠️ SIMULATION");initDataLogSim();}
        else{label="Real OBD poll";setStatus("Running ~smooth");initDataLogReal();}
        log(label+" started");
        if(no)return; // no polling, respond NO DATA to all
        pollRunning=true;
        new Thread(new Runnable(){public void run(){
            if(st)staticSimLoop();
            else if(sm)simPollLoop();
            else pollLoop();
        }}).start();
    }

    // ═══════════════════════════════════════════════════════
    // 平滑插值 — 核心算法
    // ═══════════════════════════════════════════════════════
    String interpResponse(String pid){
        if(obdFailed)return null;  // no vLinker → refuse to serve
        PidCodec codec=CODEC.get(pid);
        if(codec==null){
            // No codec — return raw value if we have poll data
            PidPoint c=curr.get(pid);
            if(c!=null){
                return "41 "+pid.substring(2)+" "+String.format("%02X",((int)c.val)&0xFF)+"\r>";
            }
            // Only return sim data in sim mode
            if(simMode||staticSim)return simRaw(pid);
            return null; // real mode with no data → don't fake it
        }
        PidPoint p,c;long now=System.currentTimeMillis();
        synchronized(interpLock){p=prev.get(pid);c=curr.get(pid);}
        if(c==null)return null;
        if(p==null||(now-c.ts)>INTERP_MS){
            // 无历史或数据太旧 — 返回最新真实值
            byte[] raw=codec.encode(c.val);
            return "41 "+pid.substring(2)+" "+bytesToHex(raw);
        }
        // 线性插值: val = p.val + (c.val-p.val) * (now-p.ts)/(c.ts-p.ts)
        long dt=c.ts-p.ts;if(dt<=0)dt=1;
        float t=clamp((float)(now-p.ts)/dt,0f,1f);
        float interpVal=p.val+(c.val-p.val)*t;
        // 如果超过当前采样点，轻推 extrapolate (最多10% overshoot within window)
        if(t>1f){
            float overshoot=Math.min((t-1f)*0.15f,0.15f);
            interpVal=c.val+(c.val-p.val)*overshoot;
        }
        byte[] raw=codec.encode(interpVal);
        return "41 "+pid.substring(2)+" "+bytesToHex(raw);
    }

    // ═══════════════════════════════════════════════════════
    // 请求分发
    // ═══════════════════════════════════════════════════════
    String proc(String cmd){String u=cmd.toUpperCase().replaceAll("\\s+","");
        // Log every DKE command — skip UI log when paused to reduce noise
        if(cmd.length()>3){if(!paused)log("DKE>> "+cmd);Log.d("OBD","DKE>> "+cmd);}
        String r=null;
        if(u.startsWith("ATSH")||u.startsWith("ATCRA")||u.startsWith("ATFCSH"))r="OK\r>";
        else if(u.equals("ATDPN"))r="6\r>";
        // ══ Block DKE AT commands from stealing vLinker poll responses ══
        else if(u.equals("ATZ"))r="ELM327 v2.3\r>";           // fake ELM version
        else if(u.equals("ATI"))r="ELM327 v2.3\r>";           // block AT I (DKE ELM ID query)
        else if(u.startsWith("ATE0")||u.startsWith("ATL0")||u.startsWith("ATS0")||u.startsWith("ATH1"))r="OK\r>";
        else if(u.startsWith("ATSP"))r="OK\r>";               // protocol - already set
        else if(u.startsWith("ATAT"))r="OK\r>";               // adaptive timing
        else if(u.startsWith("ATST"))r="OK\r>";               // set timeout
        else if(u.equals("ATAL"))r="OK\r>";                   // allow long
        else if(u.startsWith("ATCAF")||u.startsWith("ATCFC"))r="OK\r>"; // CAN filter
        else if(u.startsWith("ATFCSD")||u.startsWith("ATFCSM"))r="OK\r>"; // flow control
        else if(u.startsWith("AT"))r=raw(cmd,300);            // unknown AT → forward
        else if(u.startsWith("STPX")){
            if(cmd.contains("|")){
                String[] parts=cmd.split("\\|");
                StringBuilder allResp=new StringBuilder();
                for(String part:parts){
                    String resp=stpx(part.toUpperCase().replaceAll("\\s+",""),part);
                    if(resp!=null&&!resp.contains("NO DATA")){
                        allResp.append(resp.replace("\r>","\r"));
                    }
                }
                if(allResp.length()>0)r=allResp.toString().trim()+"\r>";
                else r=stpx(u,cmd);
            }else{
                r=stpx(u,cmd);
            }
        }
        // STN extended commands — faked for proxy compatibility
        else if(u.startsWith("STCSEGR")||u.startsWith("STCSEGT")||u.startsWith("STPRS"))r="OK\r>";
        else if(u.startsWith("STPTO"))r="OK\r>";
        // Block STBC/STBCOF — these are DKE STN config commands, do NOT forward to vLinker
        else if(u.startsWith("STBC")||u.startsWith("STBCOF"))r="OK\r>";
        // ★ STN hardware features — forward to vLinker STN2120 for CAN multi-frame HW mode
        else if(u.startsWith("STCFCPA")||u.startsWith("STFCP")||u.startsWith("STCMM"))r=raw(cmd,300);
        // STPPMA — block to prevent vLinker interference during protocol detection
        else if(u.startsWith("STPPMA"))r="OK\r>";
        else if(u.startsWith("22")&&u.length()>=6){
            String did=u.substring(0,6);
            if(obdFailed){r="NO DATA\r>";}  // no vLinker → refuse
            // Dynamic composite DID: F300 returns 9 sub-DIDs concatenated
            else if(did.equals("22F300")){r=compositeDyn(did);}
            else if(did.startsWith("22F3")||did.startsWith("22F4")){r=compositeDyn(did);} // any dynamic DID
            else {
                String obd=U2O.get(did);
                if(obd!=null){
                    String ir=interpResponse(obd);
                    if(ir!=null)r=toUds(did,ir);
                }
                if(r==null){
                // ⛔ In real OBD mode, refuse to generate fake data
                if(!simMode&&!staticSim){
                    r="NO DATA\r>";
                }else if(staticSim){
                    // Static sim fallback: return fixed 2-byte, not sine wave
                    String didHex2=did.substring(2);
                    r="62 "+didHex2.substring(0,2)+" "+didHex2.substring(2,4)+" 00 00\r>";
                }else{
                // Fallback: generate fake UDS 62 response for sim/testing only
                String didHex=did.substring(2);
                String d1=didHex.substring(0,2), d2=didHex.substring(2,4);
                long t=System.currentTimeMillis();
                double phase=t/3000.0*Math.PI*2;
                int b0=(int)(50+50*Math.sin(phase+did.hashCode()*0.01))&0xFF;
                int b1=(int)(50+50*Math.cos(phase+did.hashCode()*0.02))&0xFF;
                r="62 "+d1+" "+d2+" "+String.format("%02X",b0)+" "+String.format("%02X",b1)+"\r>";
                }
                }
            }
        }
        else if(u.startsWith("01")||u.startsWith("09"))r=raw(cmd,300);
        // UDS/KWP2000 commands (Mercedes DKE sends these in HUD mode)
        else if(u.startsWith("10"))r="50 03 00 32 01 F4\r>"; // DiagSessionControl positive
        else if(u.startsWith("3E"))r="7E\r>"; // TesterPresent positive
        else if(u.startsWith("11"))r="51\r>"; // ECU Reset positive
        else if(u.startsWith("2C")){ // DynamicallyDefineDataIdentifier
            // Parse and store composite DID definition
            parse2C(cmd);
            // 2C 02 XX YY = clear, 2C 03 XX YY = define
            // Response: 6C + echo sub-function + params
            if(u.length()>=6)r="6C "+cmd.trim().substring(3)+"\r>";
            else r="6C 03\r>";
        }
        else if(u.matches("^[0-9A-F]{2}.*"))r=raw(cmd,300); // Other hex modes
        else r=raw(cmd,300);
        if(r!=null&&r.length()>1){if(!paused)log("<<DKE "+r.trim());Log.d("OBD","<<DKE "+r.trim());}
        return r;
    }

    // ═══════════════════════════════════════════════════════
    // STPX 批量响应 — 解析 STPX D: 字段中的每个 DID
    // DKE sends: STPX H:7E0, D:22 20 00 22 50 21 ...
    // ═══════════════════════════════════════════════════════
    String stpx(String u,String rawCmd){
        // Extract D: field
        int dIdx=u.indexOf(",D:");
        if(dIdx<0)dIdx=u.indexOf("D:");
        if(dIdx<0)return raw(rawCmd,300);
        String dp=u.substring(dIdx+(u.charAt(dIdx)==','?3:2));
        int tIdx=dp.indexOf(",T:");
        if(tIdx>0)dp=dp.substring(0,tIdx);
        dp=dp.trim().replaceAll("\\s+"," "); // normalize spacing
        
        // Handle UDS service commands (2C=DynamicallyDefine, 10=Session, 3E=Tester, 11=Reset, etc.)
        if(dp.startsWith("2C")||dp.startsWith("2c")){
            // Parse 2C command to store composite DID definition
            // Build the full command string for parse2C: "2C 01 F3 00 ..."
            String fullCmd = "2C "+dp.substring(2).trim();
            parse2C(fullCmd);
            // 2C XX YY ZZ → 6C XX YY ZZ (positive response, re-space hex bytes)
            String payload=dp.substring(2).replaceAll("\\s+","");
            StringBuilder sb=new StringBuilder("6C");
            for(int i=0;i<payload.length();i+=2){
                sb.append(' ');
                if(i+1<payload.length())sb.append(payload.charAt(i)).append(payload.charAt(i+1));
                else sb.append(payload.charAt(i));
            }
            log("<<DKE (UDS 2C) "+sb.toString().trim());
            return sb.toString().trim()+"\r>";
        }
        if(dp.startsWith("10")||dp.startsWith("10")){
            return"50 03 00 32 01 F4\r>";
        }
        if(dp.startsWith("3E")||dp.startsWith("3e")){
            return"7E\r>";
        }
        if(dp.startsWith("11")||dp.startsWith("11")){
            return"51\r>";
        }
        // Handle 22 (ReadDataByIdentifier) patterns
        // Remove all spaces for DID extraction (D: field has space-separated hex bytes)
        String dpNoSpace = dp.replaceAll("\\s+","");
        java.util.ArrayList<String> dids=new java.util.ArrayList<>();
        for(int i=0;i+5<dpNoSpace.length();i++){
            if(dpNoSpace.charAt(i)=='2'&&dpNoSpace.charAt(i+1)=='2'){
                String d=dpNoSpace.substring(i,i+6);
                if(d.matches("22[0-9A-F]{4}")){dids.add(d);i+=5;}
            }
        }
        // Also handle OBD Mode 01 PIDs (e.g., 010C for RPM supplement)
        java.util.ArrayList<String> obdPids=new java.util.ArrayList<>();
        for(int i=0;i+3<dpNoSpace.length();i++){
            if(dpNoSpace.charAt(i)=='0'&&(dpNoSpace.charAt(i+1)=='1'||dpNoSpace.charAt(i+1)=='9')){
                String d=dpNoSpace.substring(i,i+4);
                if(d.matches("01[0-9A-F]{2}")||d.matches("09[0-9A-F]{2}")){obdPids.add(d);i+=3;}
            }
        }
        if(!dids.isEmpty()||!obdPids.isEmpty()){
            StringBuilder sb=new StringBuilder();
            for(String did:dids){
                // Dynamic composite DIDs F300/F400/etc → return composite using learned definition
                if(did.equals("22F300")||did.startsWith("22F3")||did.startsWith("22F4")){
                    sb.append(compositeDyn(did).replace("\r>"," "));
                    continue;
                }
                String obd=U2O.get(did);
                if(obd!=null){String ir=interpResponse(obd);if(ir!=null){String uds=toUds(did,ir);if(uds!=null&&!uds.contains("NO DATA"))sb.append(uds.replace("\r>"," "));}}
                else {
                    // Unmapped DID — only dynamic sim generates sine wave
                    String d1=did.substring(2,4),d2=did.substring(4,6);
                    if(simMode&&!staticSim){
                        long t=System.currentTimeMillis();
                        double phase=t/3000.0*Math.PI*2;
                        int b0=(int)(50+50*Math.sin(phase+did.hashCode()*0.01))&0xFF;
                        int b1=(int)(50+50*Math.cos(phase+did.hashCode()*0.02))&0xFF;
                        sb.append("62 "+d1+" "+d2+" "+String.format("%02X",b0)+" "+String.format("%02X",b1)+" ");
                    } else if(staticSim){
                        sb.append("62 "+d1+" "+d2+" 00 00 ");
                    }
                    // real car: unmapped DID → skip (no fake data)
                }
            }
            // Handle OBD Mode 01/09 PIDs (supplementary polling from DKE diagnostic)
            for(String pid:obdPids){
                String ir=interpResponse(pid);
                if(ir!=null)sb.append(ir.replace("\r>"," "));
            }
            if(sb.length()>0)return sb.toString().trim()+"\r>";
            // If sb empty, fall through to return NO DATA (not all DIDs!)
            return "NO DATA\r>";
        }
        // Fallback: unknown STPX format — return NO DATA, NOT all DIDs
        return "NO DATA\r>";
    }

    // ═══════════════════════════════════════════════════════
    // DID-based 2-byte BE encoding — each DID has its own formula
    // Input: DID + value (calibration=HUD display value, OBD=raw PID value)
    // Output: 2-byte big-endian for UDS 62 response
    // ═══════════════════════════════════════════════════════
    byte[] mercBytes(String did, float obdVal){
        if(did==null)return new byte[]{0,0};
        boolean cal=calOverride.containsKey(did);
        Integer ov=calOverride.get(did);
        if(ov!=null) obdVal=ov;  // calibration: user enters HUD display value
        float v;
        switch(did){
            case "222077": // Pos1 Boost PSI | OBD: MAP kPa | Cal: PSI
                if(cal) v=obdVal*882;                       // PSI → BE
                else    v=Math.max(0,obdVal-103)*128f;      // kPa→PSI: (kPa-103)*0.145*882≈(kPa-103)*128, 103=local baro
                break;
            case "226000": // Pos2 Torque Nm | OBD: Load% | Cal: Nm
                if(cal) v=obdVal*16;                        // Nm → BE
                else    v=obdVal*62f;                       // Load%→Nm(×390/100)→BE: Load%*3.9*16≈Load%*62
                break;
            case "222011": v=obdVal*339.8f+16590f; break;   // Pos3 Coolant: 2pt calibrated
            case "222029": v=obdVal*655; break;             // Pos4 Accel%: HUD=BE/655
            case "226040": // Pos5 Retard (old DID)
            case "22D010": // Pos5 Retard (newer DKE DID)
                if(cal) v=obdVal;
                else    v=Math.max(0, -obdVal*341.3f);
                break;
            case "222000": v=obdVal*4; break;               // Pos6 RPM: HUD=BE/4
            case "225021": v=obdVal*16; break;              // Pos7 Speed km/h: HUD=BE/16
            case "222071": // Pos8 IAT (old DID)
            case "222014": v=obdVal*339.8f+16590f; break;   // Pos8 IAT (new DID)
            case "226131": v=obdVal*655; break;             // Pos9 same as pos4
            case "22F190": v=obdVal*100; break;            // AFR: HUD=BE/100 (e.g., 1470→14.70)
            default:       v=obdVal; break;
        }
        int vi=Math.round(v);
        // Clamp to 16-bit signed range
        if(vi<-32768)vi=-32768; if(vi>65535)vi=65535;
        return new byte[]{(byte)(vi>>8),(byte)(vi&0xFF)};
    }

    // ═══════════════════════════════════════════════════════
    // UDS 62 响应构造 — did是原始UDS DID如"222000"，canResp是Mode01响应如"41 0C xx yy"
    // 输出: "62 20 00 yy\r>" (正确空格分隔, 大端序/Motorola格式)
    // ═══════════════════════════════════════════════════════
    String toUds(String did,String canResp){
        try{
            if(obdFailed)return"NO DATA\r>";  // vLinker not connected — refuse to serve fake data
            // Get OBD PID for this DID
            String obd=U2O.get(did);
            if(obd==null)return"NO DATA\r>";
            
            // Get current interpolated value
            PidPoint c=curr.get(obd);
            float val=(c!=null)?c.val:0;
            
            // Encode using DID-specific 2-byte formulas
            byte[] mb=mercBytes(did,val);
            int beVal=((mb[0]&0xFF)<<8)|(mb[1]&0xFF);
            
            // Build UDS 62 response: "62 DID_HI DID_LO BYTE0 BYTE1"
            String didHex=did.substring(2);
            String d1=didHex.substring(0,2);
            String d2=didHex.substring(2,4);
            
            StringBuilder sb=new StringBuilder();
            sb.append("62 ").append(d1).append(" ").append(d2);
            for(byte b:mb) sb.append(" ").append(String.format("%02X",b&0xFF));
            String dkeResp = sb.toString()+"\r>";
            lastDkeResp.put(did, dkeResp); // save for logging
            
            // Log full chain: raw vLinker bytes → PID value → BE encoding → DKE response
            String hud=DID_HUD.get(did);
            if(hud==null)hud=did;
            String note=simMode||staticSim?"sim":"real";
            String rawHex = lastRawHex.get(obd); // raw vLinker bytes for this PID
            logRealData(did,obd,val,rawHex,mb,beVal,dkeResp.replace("\r>",""),hud,note);
            
            return dkeResp;
        }catch(Exception e){return"NO DATA\r>";}
    }

    // ═══════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════
    byte[] extractData(String pid,String resp){
        try{
            // Find LAST Mode 01 response (most recent — ignore stale data)
            String[] p=resp.trim().split("\\s+");
            int last41=-1;
            for(int i=0;i<p.length;i++){if(p[i].equals("41")||p[i].equals("61"))last41=i;}
            if(last41<0)return null;
            java.io.ByteArrayOutputStream ba=new java.io.ByteArrayOutputStream();
            boolean skippedPid = false;
            for(int i=last41+1;i<p.length;i++){
                String x=p[i];
                if(!x.matches("[0-9A-Fa-f]{2}"))break;
                if(!skippedPid && x.equalsIgnoreCase(pid.substring(2))){skippedPid=true;continue;} // skip PID byte only once
                ba.write(Integer.parseInt(x,16));
            }
            return ba.size()>0?ba.toByteArray():null;
        }catch(Exception e){return null;}}

    String bytesToHex(byte[] b){StringBuilder sb=new StringBuilder();for(byte x:b)sb.append(String.format("%02X ",x&0xFF));return sb.toString().trim();}

    static float clamp(float v,float lo,float hi){return v<lo?lo:v>hi?hi:v;}

    String raw(String cmd,int ms){if(obdFailed)return"NO DATA\r>";if(simMode||elmW==null)return simRaw(cmd);try{elmW.print(cmd+"\r");elmW.flush();String r=readResp(ms);return r!=null?r+"\r>":null;}catch(Exception e){return null;}}
    String readResp(int ms){try{long end=System.currentTimeMillis()+ms;StringBuilder sb=new StringBuilder();while(System.currentTimeMillis()<end){if(elmR.ready()){int ch=elmR.read();if(ch==-1)break;sb.append((char)ch);}else Thread.sleep(5);}return sb.length()>0?sb.toString().trim():null;}catch(Exception e){return null;}}

    // Dynamic simulated ELM327 response for ANY command
    String simRaw(String cmd){
        String u=cmd.toUpperCase().replaceAll("\\s+","");
        if(u.equals("ATZ")||u.equals("ATE0")||u.equals("ATH0")||u.equals("ATL0")||u.equals("ATSP6")||u.equals("ATSP0"))return"OK\r>";
        if(u.startsWith("AT"))return"OK\r>";
        // Dynamic Mode 01 PID response — use sine wave simulation
        if(u.startsWith("01")&&u.length()>=4){
            String pid=u.substring(0,4); // "010C", "010D", etc.
            PidCodec codec=CODEC.get(pid);
            if(codec!=null){
                // Use interpolation engine's current value if available
                PidPoint c=curr.get(pid);
                if(c!=null){
                    byte[] raw=codec.encode(c.val);
                    return"41 "+pid.substring(2)+" "+bytesToHex(raw)+"\r>";
                }
            }
            // Fallback: generate reasonable fake value by PID type
            String pb=pid.substring(2); // "0C", "0D", etc.
            int pi=Integer.parseInt(pb,16);
            long t=System.currentTimeMillis();
            // Use time-based sine for dynamic feel
            double phase=t/3000.0*Math.PI*2;
            int val;
            if(pi==0x0C)val=(int)(2000+3000*(Math.sin(phase)+1)/2); // RPM 2000~5000
            else if(pi==0x0D)val=(int)(30+60*Math.sin(t/5000.0*Math.PI*2)+30); // Speed 30~120 km/h
            else if(pi==0x05)val=(int)(88+12*Math.sin(phase*0.7)); // Coolant 76~100°C
            else if(pi==0x0B)val=(int)(130+70*Math.sin(phase*1.3+1)); // MAP 60~200 kPa (boost!)
            else if(pi==0x11)val=(int)(25+40*Math.sin(phase*0.8+0.5)); // Throttle -15~65%
            else if(pi==0x0F)val=(int)(28+15*Math.sin(phase*0.3)); // IAT 13~43°C
            else if(pi==0x0E){val=(int)(20+25*Math.sin(phase*0.6+0.3)); int b=(int)clamp((val+64)*2,0,255); return"41 0E "+String.format("%02X",b)+"\r>";} // Timing -5~45°
            else if(pi==0x04)val=(int)(30+25*Math.sin(phase*0.8+0.5)); // Load
            else if(pi==0x22){int hi=(int)(100+150*Math.sin(phase*0.5));int lo=(int)(50+50*Math.sin(phase*0.3));return "41 22 "+String.format("%02X",hi&0xFF)+" "+String.format("%02X",lo&0xFF)+"\r>";} // Fuel Rail 2-byte
            else if(pi==0x44){int hi=0x80;int lo=(int)(10+5*Math.sin(phase*0.1));return "41 44 "+String.format("%02X",hi&0xFF)+" "+String.format("%02X",lo&0xFF)+"\r>";} // CmdAFR 2-byte (~1.0 eq)
            else if(pi==0x0A)val=(int)(280+50*Math.sin(phase*1.1)); // Fuel pressure kPa
            else if(pi==0x2F){val=(int)(50+20*Math.sin(phase*0.2)); return"41 2F "+String.format("%02X",val&0xFF)+"\r>";} // Fuel level
            else if(pi==0x46)val=(int)(28+8*Math.sin(phase*0.1)); // Ambient
            else if(pi==0x33)val=100; // Baro
            else if(pi==0x10){int hi=(int)(10+5*Math.sin(phase)); return"41 10 "+String.format("%02X",hi&0xFF)+" 00\r>";} // MAF
            else val=(int)(50+50*Math.sin(phase*pi/255.0+pi)); // Generic
            int v=(int)clamp(val,0,255);
            return"41 "+pb+" "+String.format("%02X",v&0xFF)+"\r>";
        }
        // Mode 09 VIN — return fake VIN
        if(u.startsWith("09"))return"49 02 01 00 00 00 00 34\r>";
        // Unknown command
        return"NO DATA\r>";
    }

    void cleanup(){running=false;pollRunning=false;try{dkeSock.close();}catch(Exception e){}try{srvSock.close();}catch(Exception e){}try{elmSock.close();}catch(Exception e){}try{tcpSock.close();}catch(Exception e){}closeLog();setStatus("Disconnected");}

    private static final int MAX_LOG_LINES = 500; // prevent OOM
    void log(final String m){
        final String line=TS.format(new java.util.Date())+" "+m;
        android.util.Log.d("OBDProxy",line);
        if(logFile!=null){synchronized(logFile){logFile.println(line);logFile.flush();}}
        h.post(new Runnable(){public void run(){
            // Limit log lines to prevent OOM
            CharSequence cs = logView.getText();
            int lineCount = logView.getLineCount();
            if(lineCount > MAX_LOG_LINES){
                // Trim oldest ~20% lines
                int trimTo = MAX_LOG_LINES * 4 / 5;
                int cutOff = logView.getLayout().getLineStart(lineCount - trimTo);
                CharSequence trimmed = cs.subSequence(cutOff, cs.length());
                logView.setText(trimmed);
            }
            logView.append(line+"\n");
            // Auto-scroll to bottom — simple, no lag
            if(logScroll!=null){
                logScroll.postDelayed(new Runnable(){public void run(){
                    logScroll.fullScroll(ScrollView.FOCUS_DOWN);
                }}, 50);
            }
        }});
    }
    void initLogFile(){
        try{
            java.io.File dir=getExternalFilesDir(null);
            if(dir!=null&&!dir.exists())dir.mkdirs();
            String ts=new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            java.io.File f=new java.io.File(dir,"obdproxy_"+ts+".log");
            currentLogPath=f.getAbsolutePath();
            logFile=new PrintWriter(new java.io.FileWriter(f,true),true);
            // Data log inited separately by mode (see initDataLogSim/initDataLogReal)
            log("Log file: "+currentLogPath);
        }catch(Exception e){android.util.Log.e("OBDProxy","Cannot create log file",e);}
    }
    // Init data CSV for simulation modes
    void initDataLogSim(){
        try{
            java.io.File dir=getExternalFilesDir(null);
            if(dir!=null&&!dir.exists())dir.mkdirs();
            String ts=new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            java.io.File df=new java.io.File(dir,"obddata_sim_"+ts+".csv");
            dataLog=new PrintWriter(new java.io.FileWriter(df,true),true);
            dataLogPath=df.getAbsolutePath();
            // ── Unified 8-column format (same as real) ──
            dataLog.println("time,did,pid,name,value,vlinker_raw,be_hex,be_val,dke_resp,hud,note");
            dataLog.println("# Started: "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            dataLog.println("# Mode: SIM (Dynamic or Static)");
            dataLog.println("# SIM rows: did=pid, vlinker_raw=SIM, be_hex=, dke_resp=, note=SIM");
            dataLog.println("# DKE rows: full raw→PID→BE→62 response chain, note=sim");
            dataLog.flush();
            final String shortPath=".../files/obddata_sim_"+ts+".csv";
            h.post(new Runnable(){public void run(){logPathView.setText("📁 "+shortPath);}});
            log("Data log (sim): "+dataLogPath);
        }catch(Exception e){android.util.Log.e("OBDProxy","Cannot create sim data log",e);}
    }
    // Init data CSV for real vLinker — full chain: OBD → UDS mapping → BE output
    void initDataLogReal(){
        try{
            java.io.File dir=getExternalFilesDir(null);
            if(dir!=null&&!dir.exists())dir.mkdirs();
            String ts=new SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
            java.io.File df=new java.io.File(dir,"obddata_real_"+ts+".csv");
            dataLog=new PrintWriter(new java.io.FileWriter(df,true),true);
            dataLogPath=df.getAbsolutePath();
            // Header: two row formats in one CSV
            // Format1 (conn): time,CONN,stage,detail
            // Format2 (data): time,did,obd_pid,obd_val,be_hex,be_val,hud_param,note
            dataLog.println("time,type,field1,field2,field3,field4,field5,field6,field7,field8");
            dataLog.println("# Started: "+new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
            dataLog.println("# device="+(elmDev!=null?elmDev.getName()+" ("+elmDev.getAddress()+")":"unknown"));
            dataLog.println("# CONN rows: type=CONN, field1=stage, field2=detail");
            dataLog.println("# DATA rows: type=did, field1=obd_pid, field2=obd_val, field3=vlinker_raw_hex, field4=be_hex, field5=be_val, field6=dke_62_response, field7=hud_param, field8=note");
            dataLog.flush();
            final String shortPath=".../files/obddata_real_"+ts+".csv";
            h.post(new Runnable(){public void run(){logPathView.setText("📁 "+shortPath);}});
            log("Data log (real): "+dataLogPath);
        }catch(Exception e){android.util.Log.e("OBDProxy","Cannot create real data log",e);}
    }
    // Log simulation data (simple: pid + value)
    void logSimData(String pid, float val){
        logPollData(pid, val, "SIM");
    }
    void logPollData(String pid, float val, String note){
        if(dataLog==null)return;
        String name=pid; switch(pid){
            case "010C":name="RPM";break;case "010D":name="Speed";break;
            case "0105":name="Coolant";break;case "010B":name="MAP";break;
            case "0111":name="Throttle";break;case "010F":name="IAT";break;
            case "010E":name="Timing";break;case "0104":name="Load";break;
            case "0122":name="FuelRail";break;
            case "0144":name="CmdAFR";break;case "0124":name="AFR";break;
        }
        dataLog.println(TS.format(new java.util.Date())+","+pid+","+pid+","+name+","+String.format("%.1f",val)+","+note+",,,,"+note);
        dataLog.flush();
    }
    // Log real OBD chain: raw vLinker → PID → BE bytes → DKE response
    void logRealData(String did, String obdPid, float obdVal, String rawHex, byte[] beBytes, int beVal, String dkeResp, String hudName, String note){
        if(dataLog==null)return;
        String beHex=String.format("%02X%02X",beBytes[0]&0xFF,beBytes[1]&0xFF);
        dataLog.println(TS.format(new java.util.Date())+","+did+","+obdPid+","+
            String.format("%.1f",obdVal)+","+(rawHex!=null?rawHex:"")+","+beHex+","+beVal+","+
            dkeResp+","+hudName+","+note);
        dataLog.flush();
    }
    // Log vLinker connection process into the real data CSV
    // Format: time,CONN,stage,detail,,,,, (8 columns)
    void logConnData(String stage, String detail){
        if(dataLog==null)return;
        dataLog.println(TS.format(new java.util.Date())+",CONN,"+stage+","+
            detail.replace("\r","\\r").replace("\n","\\n").replace(",",";")+",,,,,");
        dataLog.flush();
    }
    static final java.util.Map<String,String> DID_HUD = new java.util.HashMap<>();
    static {
        DID_HUD.put("222077","Boost_PSI");DID_HUD.put("226000","Torque_Nm");
        DID_HUD.put("222011","Coolant_C");DID_HUD.put("222029","Accel_pct");
        DID_HUD.put("226040","Retard");
        DID_HUD.put("22D010","Retard");DID_HUD.put("222000","RPM");
        DID_HUD.put("225021","Speed_kmh");DID_HUD.put("222071","IAT_C");
        DID_HUD.put("226131","CoolantAlt");
    }
    void closeLog(){
        if(logFile!=null){try{logFile.close();}catch(Exception e){}}
        logFile=null;
        if(dataLog!=null){try{dataLog.close();}catch(Exception e){}}
        dataLog=null;
    }
    void setStatus(final String s){h.post(new Runnable(){public void run(){statusView.setText(s);}});}
}
