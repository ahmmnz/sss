package com.googletv.kumanda;

import android.Manifest;
import android.bluetooth.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import android.content.SharedPreferences;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    int ses=24, kanal=7;
    boolean isWifiMode=true;
    StringBuilder channelInput=new StringBuilder();
    TextView volText,chText,statusText;
    EditText ipInput;
    LinearLayout wifiLayout,btLayout;
    Spinner btSpinner;
    ArrayAdapter<String> btAdapter;
    List<BluetoothDevice> btDevices=new ArrayList<>();
    BluetoothAdapter btAdapterHW;
    BluetoothSocket btSocket;
    OutputStream btOut;

    // ADB
    Socket adbSocket;
    DataInputStream adbIn;
    DataOutputStream adbOut;
    boolean adbConnected=false;
    int localId=1;
    static final int A_CNXN=0x4e584e43, A_OPEN=0x4e45504f, A_OKAY=0x59414b4f, A_CLSE=0x45534c43;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        volText=findViewById(R.id.volText); chText=findViewById(R.id.chText); statusText=findViewById(R.id.statusText);
        ipInput=findViewById(R.id.ipInput); wifiLayout=findViewById(R.id.wifiLayout); btLayout=findViewById(R.id.btLayout);
        btSpinner=findViewById(R.id.btDeviceSpinner);
        btAdapterHW=BluetoothAdapter.getDefaultAdapter();
        btAdapter=new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        btAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        btSpinner.setAdapter(btAdapter);
        SharedPreferences prefs=getSharedPreferences("gtv",MODE_PRIVATE);
        ipInput.setText(prefs.getString("tv_ip",""));

        RadioGroup modeGroup=findViewById(R.id.modeGroup);
        modeGroup.setOnCheckedChangeListener((g,id)->{
            if(id==R.id.radioWifi){ isWifiMode=true; wifiLayout.setVisibility(LinearLayout.VISIBLE); btLayout.setVisibility(LinearLayout.GONE); statusText.setText(adbConnected?"WiFi Bagli":"WiFi - IP gir"); }
            else{ isWifiMode=false; wifiLayout.setVisibility(LinearLayout.GONE); btLayout.setVisibility(LinearLayout.VISIBLE); statusText.setText("Bluetooth - Tara"); checkPerm(); }
        });

        findViewById(R.id.btnWifiConnect).setOnClickListener(v->{
            String ip=ipInput.getText().toString().trim();
            if(ip.isEmpty()){ toast("IP gir"); return; }
            prefs.edit().putString("tv_ip",ip).apply();
            connectAdb(ip);
        });

        findViewById(R.id.btnBtEnable).setOnClickListener(v->{
            if(btAdapterHW==null){ toast("BT yok"); return; }
            if(!btAdapterHW.isEnabled()){
                if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
                    ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return;
                }
                btAdapterHW.enable(); toast("BT aciliyor");
            } else toast("BT zaten acik");
        });
        findViewById(R.id.btnBtScan).setOnClickListener(v->scanBt());
        findViewById(R.id.btnBtConnect).setOnClickListener(v->{
            if(btDevices.isEmpty()){ toast("Once Tara"); return; }
            int pos=btSpinner.getSelectedItemPosition();
            if(pos<0||pos>=btDevices.size()){ toast("Cihaz sec"); return; }
            connectBt(btDevices.get(pos));
        });

        findViewById(R.id.btnPower).setOnClickListener(v->sendKey(26,"POWER"));
        findViewById(R.id.btnMute).setOnClickListener(v->sendKey(164,"MUTE"));
        findViewById(R.id.btnUp).setOnClickListener(v->sendKey(19,"UP"));
        findViewById(R.id.btnDown).setOnClickListener(v->sendKey(20,"DOWN"));
        findViewById(R.id.btnLeft).setOnClickListener(v->sendKey(21,"LEFT"));
        findViewById(R.id.btnRight).setOnClickListener(v->sendKey(22,"RIGHT"));
        findViewById(R.id.btnOk).setOnClickListener(v->sendKey(23,"OK"));
        findViewById(R.id.btnBack).setOnClickListener(v->sendKey(4,"BACK"));
        findViewById(R.id.btnHome).setOnClickListener(v->sendKey(3,"HOME"));
        findViewById(R.id.btnMenu).setOnClickListener(v->sendKey(82,"MENU"));
        findViewById(R.id.btnVolUp).setOnClickListener(v->sendKey(24,"VOL_UP",()->{ if(ses<100)ses++; updateUI(); }));
        findViewById(R.id.btnVolDown).setOnClickListener(v->sendKey(25,"VOL_DOWN",()->{ if(ses>0)ses--; updateUI(); }));
        findViewById(R.id.btnChUp).setOnClickListener(v->sendKey(166,"CH_UP",()->{ kanal++; if(kanal>999)kanal=1; updateUI(); }));
        findViewById(R.id.btnChDown).setOnClickListener(v->sendKey(167,"CH_DOWN",()->{ kanal--; if(kanal<1)kanal=999; updateUI(); }));
        findViewById(R.id.btn0).setOnClickListener(v->numPress(7,"0"));
        findViewById(R.id.btn1).setOnClickListener(v->numPress(8,"1"));
        findViewById(R.id.btn2).setOnClickListener(v->numPress(9,"2"));
        findViewById(R.id.btn3).setOnClickListener(v->numPress(10,"3"));
        findViewById(R.id.btn4).setOnClickListener(v->numPress(11,"4"));
        findViewById(R.id.btn5).setOnClickListener(v->numPress(12,"5"));
        findViewById(R.id.btn6).setOnClickListener(v->numPress(13,"6"));
        findViewById(R.id.btn7).setOnClickListener(v->numPress(14,"7"));
        findViewById(R.id.btn8).setOnClickListener(v->numPress(15,"8"));
        findViewById(R.id.btn9).setOnClickListener(v->numPress(16,"9"));
        findViewById(R.id.btnClear).setOnClickListener(v->{ channelInput.setLength(0); updateUI(); });
        findViewById(R.id.btnEnter).setOnClickListener(v->{ if(channelInput.length()>0){ try{ kanal=Integer.parseInt(channelInput.toString()); channelInput.setLength(0); updateUI(); sendKey(66,"ENTER"); }catch(Exception e){} } });
        updateUI();
    }

    void scanBt(){
        checkPerm();
        if(btAdapterHW==null||!btAdapterHW.isEnabled()){ toast("BT acik degil"); return; }
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return; }
        Set<BluetoothDevice> paired=btAdapterHW.getBondedDevices();
        btDevices.clear(); List<String> names=new ArrayList<>();
        for(BluetoothDevice d:paired){ btDevices.add(d); names.add(d.getName()+" ("+d.getAddress()+")"); }
        if(names.isEmpty()) names.add("Eslesmis cihaz yok");
        btAdapter.clear(); btAdapter.addAll(names); btAdapter.notifyDataSetChanged();
        toast(names.size()+" cihaz");
    }

    void connectBt(BluetoothDevice dev){
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.BLUETOOTH_CONNECT},1); return; }
        statusText.setText("BT baglaniyor: "+dev.getName());
        new Thread(()->{
            try{
                java.util.UUID uuid=java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
                btSocket=dev.createRfcommSocketToServiceRecord(uuid);
                btSocket.connect();
                btOut=btSocket.getOutputStream();
                runOnUiThread(()->{ statusText.setText("BT Bagli: "+dev.getName()); toast("BT Baglandi"); });
            }catch(Exception e){ runOnUiThread(()->{ statusText.setText("BT hata: "+e.getMessage()); toast("BT baglanamadi: "+e.getMessage()); }); }
        }).start();
    }

    void checkPerm(){
        List<String> p=new ArrayList<>();
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!p.isEmpty()) ActivityCompat.requestPermissions(this,p.toArray(new String[0]),1);
    }

    void connectAdb(String ip){
        statusText.setText("WiFi ADB baglaniyor "+ip+":5555");
        new Thread(()->{
            try{
                if(adbSocket!=null) try{adbSocket.close();}catch(Exception e){}
                adbSocket=new Socket(); adbSocket.connect(new java.net.InetSocketAddress(ip,5555),5000);
                adbIn=new DataInputStream(adbSocket.getInputStream()); adbOut=new DataOutputStream(adbSocket.getOutputStream());
                sendAdb(A_CNXN,0x01000000,256*1024,"host::\0".getBytes());
                AdbMsg r=readAdb();
                adbConnected=true;
                runOnUiThread(()->{ statusText.setText("WiFi ADB Bagli: "+ip); toast("ADB Baglandi"); });
            }catch(Exception e){
                adbConnected=false;
                runOnUiThread(()->{ statusText.setText("WiFi hata: "+e.getMessage()); toast("Baglanamadi: TV'de Ag Hata Ayiklama AC"); });
            }
        }).start();
    }

    void sendAdb(int cmd,int a0,int a1,byte[] data) throws IOException{
        if(data==null) data=new byte[0];
        ByteBuffer b=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd); b.putInt(a0); b.putInt(a1); b.putInt(data.length); b.putInt(checksum(data)); b.putInt(cmd ^ 0xFFFFFFFF);
        adbOut.write(b.array()); if(data.length>0) adbOut.write(data); adbOut.flush();
    }
    AdbMsg readAdb() throws IOException{
        byte[] h=new byte[24]; adbIn.readFully(h);
        ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int cmd=b.getInt(),a0=b.getInt(),a1=b.getInt(),len=b.getInt(),crc=b.getInt(),mag=b.getInt();
        byte[] d=new byte[len]; if(len>0) adbIn.readFully(d);
        return new AdbMsg(cmd,a0,a1,d);
    }
    int checksum(byte[] d){ int s=0; for(byte x:d) s+=x&0xFF; return s; }
    static class AdbMsg{ int c,a0,a1; byte[] d; AdbMsg(int c,int a0,int a1,byte[] d){this.c=c;this.a0=a0;this.a1=a1;this.d=d;} }

    void execAdb(int keyCode){
        if(!adbConnected) return;
        new Thread(()->{
            try{
                String cmd="shell:input keyevent "+keyCode+"\0";
                sendAdb(A_OPEN,localId++,0,cmd.getBytes());
                for(int i=0;i<2;i++) try{readAdb();}catch(Exception e){}
            }catch(Exception e){}
        }).start();
    }

    void sendKey(int code,String name){ sendKey(code,name,null); }
    void sendKey(int code,String name,Runnable ok){
        if(isWifiMode){
            if(adbConnected){ execAdb(code); toast(name+" -> WiFi "+code); }
            else toast(name+" [WiFi bagli degil]");
        }else{
            if(btSocket!=null && btSocket.isConnected() && btOut!=null){
                try{ btOut.write((name+"\n").getBytes()); toast(name+" -> BT"); }catch(Exception e){ toast("BT hata"); }
            }else toast(name+" [BT bagli degil]");
        }
        if(ok!=null) ok.run();
    }

    void numPress(int code,String n){ channelInput.append(n); chText.setText(channelInput.toString()); sendKey(code,"NUM_"+n); if(channelInput.length()>=3){ try{ kanal=Integer.parseInt(channelInput.toString()); channelInput.setLength(0); updateUI(); }catch(Exception e){ channelInput.setLength(0); } } }
    void updateUI(){ volText.setText(String.valueOf(ses)); if(channelInput.length()==0) chText.setText(String.valueOf(kanal)); }
    void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }
}
