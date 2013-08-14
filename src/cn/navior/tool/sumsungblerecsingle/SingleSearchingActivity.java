package cn.navior.tool.sumsungblerecsingle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.widget.*;

import cn.naviro.tool.sumsungblerecsingle.R;
import com.samsung.android.sdk.bt.gatt.BluetoothGatt;
import com.samsung.android.sdk.bt.gatt.BluetoothGattAdapter;
import com.samsung.android.sdk.bt.gatt.BluetoothGattCallback;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.*;

public class SingleSearchingActivity extends Activity {

  // constants defined by wangxiayang
  private static final int REQUEST_ENABLE_BT = 36287;
  private static final int MAX_PIXEL_X = 1080;  // sumsung galaxy s4's configuration
  private static final int MAX_PIXEL_Y = 1920;  // sumsung galaxy s4's configuration

  // components
  private TextView searchingStatus;
  private EditText distanceInput;
  private EditText nameInput;
  private EditText clockInput;
  private Button stopButton;
  private Button startButton;
  private Button enableClockButton;
  private Button quitButton;
  private WaveGraph waveGraph;
  private DistributionGraph distributionGraph;
  private TableLayout resultStat;

  // status
  private boolean clockEnabled;

  // tools
  private Thread clock;
  private PrintWriter writer;
  private Handler handler;
  private HashMap< Integer, ArrayList< RecordItem > > recordsOnGraph;
  private ArrayList< RecordItem > tempRecords;  // temporary record storage

  // fields about local Bluetooth device model
  private BluetoothAdapter mBluetoothAdapter; // local Bluetooth device model
  private BluetoothGatt mBluetoothGatt = null;    // local Bluetooth BLE device model
  private BluetoothGattCallback mGattCallbacks = new BluetoothGattCallback() {
    @Override
    public void onScanResult( final android.bluetooth.BluetoothDevice device, final int rssi, byte[] scanRecord) {
      // only deal with certain device
      if( device.getName().equals( nameInput.getEditableText().toString() ) ){
        // initialize the record
        RecordItem item = new RecordItem( device.getAddress() );
        item.setRssi( rssi );	// replace the short value into an integer
        item.setName( device.getName() );
        item.setDistance( Integer.parseInt( distanceInput.getEditableText().toString() ) );
        SimpleDateFormat tempDate = new SimpleDateFormat( "kk-mm-ss-SS", Locale.ENGLISH );
        String datetime = tempDate.format(new java.util.Date());
        item.setDatetime( datetime );
        // put into HashMap
        if( recordsOnGraph.containsKey( rssi ) ) {
          recordsOnGraph.get( rssi ).add( item );
        }
        else {
          ArrayList< RecordItem > list = new ArrayList<RecordItem>();
          list.add( item );
          recordsOnGraph.put( rssi, list );
        }
        // put into temporary storage
        tempRecords.add( item );

        // update searching status
        searchingStatus.post( new Runnable() {
          @Override
          public void run() {
            searchingStatus.setText( "Found " + device.getName() + " with rssi " + rssi );
          }
        } );

        waveGraph.postInvalidate();
        distributionGraph.postInvalidate();
      }
    }
  };  // message handler
  private BluetoothProfile.ServiceListener mProfileServiceListener = new BluetoothProfile.ServiceListener() {
    @Override
    public void onServiceConnected(int profile, BluetoothProfile proxy) {
      if (profile == BluetoothGattAdapter.GATT) {
        mBluetoothGatt = (BluetoothGatt) proxy;
        mBluetoothGatt.registerApp(mGattCallbacks);
      }
    }

    @Override
    public void onServiceDisconnected(int profile) {
      if (profile == BluetoothGattAdapter.GATT) {
        if (mBluetoothGatt != null)
          mBluetoothGatt.unregisterApp();

        mBluetoothGatt = null;
      }
    }
  };  // device model builder

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_single_searching);

		// initialize the fields
    searchingStatus = ( TextView )findViewById( R.id.searching_status );
    distanceInput = ( EditText ) findViewById( R.id.input_distence );
    nameInput = ( EditText ) findViewById( R.id.input_name );
    clockInput = ( EditText ) findViewById( R.id.input_clock );
    stopButton = ( Button )findViewById( R.id.searching_stop );
    startButton = ( Button ) findViewById( R.id.searching_start );
    enableClockButton = ( Button ) findViewById( R.id.searching_enable_clock );
    quitButton = ( Button )findViewById( R.id.searching_quit );
    clockEnabled = false;
    handler = new Handler();
    tempRecords = new ArrayList< RecordItem >();
    recordsOnGraph = new HashMap< Integer, ArrayList< RecordItem > >();
    resultStat = new TableLayout( this );

    // build up local Bluetooth device model
    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();	// get the Bluetooth adapter for this device
    if ( mBluetoothGatt == null ) {
      BluetoothGattAdapter.getProfileProxy( this, mProfileServiceListener, BluetoothGattAdapter.GATT );
    }

		// bind the action listeners
    // stop-searching button
    stopButton.setOnClickListener( new View.OnClickListener(){
      public void onClick( View v ){
        onStopScan();
      }
    });
    stopButton.setEnabled( false );	// disable the stop button until discovery starts
    // start-searching button
    startButton.setOnClickListener( new View.OnClickListener(){
      public void onClick( View v ){
        onStartScan();
      }
    });
    // enable-clock button
    enableClockButton.setOnClickListener( new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // if clock has been enabled, disable it
        if( clockEnabled ) {
          clockEnabled = false;
          enableClockButton.setText( "enable clock" );
          clockInput.setEnabled( false );
        }
        else {
          clockEnabled = true;
          enableClockButton.setText( "disable clock" );
          clockInput.setEnabled( true );
        }
      }
    });
    // quit-searching button
    quitButton.setOnClickListener( new View.OnClickListener(){
      public void onClick( View v ){
        SingleSearchingActivity.this.finish();
      }
    });

    // add the graphs
    waveGraph = new WaveGraph( this );
    waveGraph.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, 500 ) );
    ( ( LinearLayout )findViewById( R.id.searching_graph_board ) ).addView( waveGraph );
    distributionGraph = new DistributionGraph( this );
    distributionGraph.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, 500 ) );
    ( ( LinearLayout )findViewById( R.id.searching_graph_board ) ).addView( distributionGraph );

    // add the result statistic table
    ( ( LinearLayout )findViewById( R.id.searching_graph_board ) ).addView( resultStat );
  }

  @Override
  protected void onResume() {
    super.onResume();
		// check Bluetooth status, notify user to turn it on if it's not
    // repeat requesting if user refused to open Bluetooth. It's ugly now but no matter.
    while (!mBluetoothAdapter.isEnabled()) {
      Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
      startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
    }
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    BluetoothGattAdapter.closeProfileProxy( BluetoothGattAdapter.GATT, mBluetoothGatt );
  }

  /**
   * logic on starting scanning
   */
  private void onStartScan() {
    // clear the storage
    tempRecords.clear();
    recordsOnGraph.clear();
    resultStat.removeAllViews();
    // check input validity
    if( !checkInput() ) {
      // notify user if invalid
      Toast.makeText( SingleSearchingActivity.this, "sth wrong in your input", Toast.LENGTH_SHORT ).show();
      return;
    }
    // start only if distance and device name are set
    createPrintWriter();
    mBluetoothGatt.startScan();
    // if clock is enabled, start clock
    if( clockEnabled ) {
       clock = new Thread() {
        @Override
        public void run() {
          try {
            Thread.sleep( Integer.parseInt( clockInput.getEditableText().toString() ) );
          } catch (InterruptedException e) {
            e.printStackTrace();
          } finally {
            handler.post( new Runnable() {
              @Override
              public void run() {
                // stop scan if time is up
                onStopScan();
              }
            } );
          }
        }
      };
      clock.start();
    }
    // present the distance and name on status bar
    searchingStatus.setText( "on discovering" );
    // disable the buttons and input area
    stopButton.setEnabled( true );  // only enable stop button
    startButton.setEnabled( false );
    enableClockButton.setEnabled( false );
    nameInput.setEnabled( false );
    distanceInput.setEnabled( false );
    clockInput.setEnabled( false );
  }

  /**
   * check input validity
   * @return
   */
  private boolean checkInput() {
    // check distance input
    if( distanceInput.getEditableText() == null
        || distanceInput.getEditableText().toString().equals( "" ) ){
      return false;
    }
    else {
      try {
        Integer.parseInt( distanceInput.getEditableText().toString() );
      } catch ( NumberFormatException e ) {
        return false;
      }
    }
    // check clock input
    if( clockEnabled ){
      if( clockInput.getEditableText() == null
          || clockInput.getEditableText().toString().equals( "" ) ){
        return false;
      }
      else {
        try {
          Integer.parseInt( clockInput.getEditableText().toString() );
        } catch ( NumberFormatException e ) {
          return false;
        }
      }
    }
    // check name input
    if( nameInput.getEditableText() == null
        || nameInput.getEditableText().toString().equals( "" ) ){
      return false;
    }

    // all pass
    return true;
  }

  private void createPrintWriter() {
    // create directory
    File directory = new File( Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "sumsung_rssirec_single" );
    if( !directory.exists() ) {
      directory.mkdir();
    }

    // create the time string
    SimpleDateFormat tempDate = new SimpleDateFormat( "yyyy-MM-dd-kk-mm-ss", Locale.ENGLISH );
    String datetime = tempDate.format(new java.util.Date());

    // create the file
    File recordFile = new File( directory.getAbsolutePath() + "/" + datetime + ".txt" );
    if( recordFile.exists() ) {
      recordFile.delete();
    }

    // open writer
    try {
      writer = new PrintWriter( recordFile );
      writer.write( "Device name," + mBluetoothAdapter.getName() + "\n" );
      writer.write( "Device address," + mBluetoothAdapter.getAddress() + "\n" );
      writer.write( "Discovered device name," + nameInput.getEditableText().toString() );
      writer.write( "Discovered device distance," + distanceInput.getEditableText().toString() );
      writer.write( "start," + datetime + "\n" );
      writer.write( "distance,rssi,time\n" );
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
  }

  /**
   * logic on stop scanning
   */
  private void onStopScan() {
    mBluetoothGatt.stopScan();
    // check if there is a clock thread running
    if( clock != null ) {
      // if this is the main thread running on this logic
      if( clock.getId() != Thread.currentThread().getId() ) {
        // kill the clock
        clock.interrupt();
      }
    }
    clock = null; // clear the clock
    searchingStatus.setText( "Discovery has finished." );
    // show the statistic results
    showStatisticResults();
    // write records
    saveRecords();
    // repaint
    waveGraph.postInvalidate();
    distributionGraph.postInvalidate();
    // write stop info
    SimpleDateFormat tempDate = new SimpleDateFormat( "kk-mm-ss-SS", Locale.ENGLISH );
    String datetime = tempDate.format(new java.util.Date());
    writer.write( "stop," + datetime );
    writer.close();
    // enable the buttons
    stopButton.setEnabled( false );  // only disable stop button
    startButton.setEnabled( true );
    enableClockButton.setEnabled( true );
    nameInput.setEnabled( true );
    distanceInput.setEnabled( true );
    if( clockEnabled ){
      clockInput.setEnabled( true );
    }
  }

  private void showStatisticResults() {
    resultStat.post( new Runnable() {
      @Override
      public void run() {
        TableRow row = new TableRow( SingleSearchingActivity.this );
        TextView t = new TextView( SingleSearchingActivity.this );
        t.setText( "RSSI    " );
        row.addView( t );
        t = new TextView( SingleSearchingActivity.this );
        t.setText( "count   " );
        row.addView( t );
        t = new TextView( SingleSearchingActivity.this );
        t.setText( "percentage" );
        row.addView( t );
        resultStat.addView( row );
      }
    } );
    // get total record size
    final int recordNum = tempRecords.size();
    // get overall average
    ArrayList< Integer > rssiList = new ArrayList< Integer >();
    for( int i = 0; i < tempRecords.size(); i++ ) {
      rssiList.add( tempRecords.get( i ).getRssi() );
    }
    final int average = ( int )MyMathematicalMachine.getArithmeticAverage( rssiList );
    resultStat.post( new Runnable() {
      @Override
      public void run() {
        TableRow row = new TableRow( SingleSearchingActivity.this );
        TextView t = new TextView( SingleSearchingActivity.this );
        t.setText( "" + average );
        row.addView(t);
        t = new TextView( SingleSearchingActivity.this );
        t.setText( "" + recordNum );
        row.addView(t);
        t = new TextView( SingleSearchingActivity.this );
        t.setText( "1" );
        row.addView( t );
        resultStat.addView( row );
      }
    } );
    // get count for each rssi
    TreeSet< Integer > keys = new TreeSet< Integer >( recordsOnGraph.keySet() );
    // from strongest to weakest
    int times = keys.size();
    for( int i = 0 ; i < times; i++ ) {
      final int rssiValue = keys.pollLast();
      final int count = recordsOnGraph.get( rssiValue ).size();
      final double percentage = ( count + 0.0 ) / recordNum;
      resultStat.post( new Runnable() {
        @Override
        public void run() {
          TableRow row = new TableRow( SingleSearchingActivity.this );
          TextView t = new TextView( SingleSearchingActivity.this );
          t.setText( "" + rssiValue );
          row.addView( t );
          t = new TextView( SingleSearchingActivity.this );
          t.setText( "" + count );
          row.addView( t );
          t = new TextView( SingleSearchingActivity.this );
          t.setText( "" + ( int )( percentage * 100 ) + "%" );
          row.addView( t );
          resultStat.addView( row );
        }
      } );
    }
  }

  /**
   * Not handle NULLPOINTER exception for that writer is null
   */
  private void saveRecords() {
    for( int i = 0; i < tempRecords.size(); i++ ) {
      RecordItem item = tempRecords.get( i );
      writer.write( item.getDistance() + "," + item.getRssi() + "," + item.getDatetime() + "\n" );
    }
  }

  class WaveGraph extends View {

    private final static int BASE_X = 20;
    private final static int BASE_Y = 20;
    private final static int MARGIN_X = 50;
    private final static int RULER_LENGTH = 20;

    private final static int GAP = 21;
    private final static int RADIUS = 10;

    private int gap;  // gap between two points on the graph
    private int radius; // radius of a point
    private int amplifier;

    WaveGraph( Context context ) {
      super( context );

      gap = GAP;
      radius = RADIUS;
      amplifier = 50;
    }

    @Override
    protected void onDraw( Canvas canvas ) {
      super.onDraw(canvas);
      gap = GAP;
      radius = RADIUS;

      // ordered set, the lowest value comes first
      TreeSet< Integer > keys = new TreeSet< Integer >( recordsOnGraph.keySet() );
      if( !keys.isEmpty() && keys.size() >= 2 ) {
        int lastValue = keys.pollLast();
        keys.add( lastValue );
        int firstValue = keys.pollFirst();
        keys.add( firstValue );
        // adjust the height*
        this.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, ( lastValue - firstValue + 2 ) * amplifier ) );
        // adjust the gap and radius
        while( gap * tempRecords.size() + MARGIN_X + RULER_LENGTH >= MAX_PIXEL_X ) {
          gap--;
        }  // too wide for the graph
        radius = ( gap + 1 ) / 2 - 1;
        // draw the vertical rulers
        for( int i = 0; i < lastValue - firstValue + 1; i++ ) {
          Paint painter = new Paint();
          canvas.drawLine( BASE_X, BASE_Y + i * amplifier, MAX_PIXEL_X - BASE_X, BASE_Y + i * amplifier, painter );
        }
        // draw the horizontal rulers
        for( int i = 0; i < tempRecords.size(); i += 10 ) {
          Paint painter = new Paint();
          canvas.drawLine( BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y, BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y + ( lastValue - firstValue + 1 ) * amplifier, painter );
        }
        // draw the points
        for( int i = 0; i < tempRecords.size(); i++ ) {
          Paint painter = new Paint();
          canvas.drawCircle( BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y + ( -tempRecords.get( i ).getRssi() + lastValue ) * amplifier, radius, painter );
        }
      }
    }

  }

  class DistributionGraph extends View {

    private final static int BASE_X = 20;
    private final static int BASE_Y = 20;
    private final static int MARGIN_X = 50;

    private int gap;
    private int width;
    private int amplifier;

    DistributionGraph( Context context ) {
      super( context );

      gap = 20;
      width = 50;
      amplifier = 20;
    }

    protected void onDraw( Canvas canvas ){
      super.onDraw(canvas);
      // draw the graph
      TreeSet< Integer > keys = new TreeSet< Integer >( recordsOnGraph.keySet() );
      if( !keys.isEmpty() && keys.size() >= 2 ) {
        // adjust the height
        int maxAmount = 0;
        Iterator< Integer > iterator = keys.descendingIterator();
        while( iterator.hasNext() ) {
          int rssiValue = iterator.next();
          if( recordsOnGraph.get( rssiValue ).size() > maxAmount )
          {
            maxAmount = recordsOnGraph.get( rssiValue ).size();
          }
        }
        this.setLayoutParams( new LinearLayout.LayoutParams( LinearLayout.LayoutParams.MATCH_PARENT, ( maxAmount + 1 ) * amplifier ) );
        // adjust gap and width
        while( ( gap + width ) * keys.size() + 2 * MARGIN_X >= MAX_PIXEL_X ) {
          gap /= 2;
          width /= 2;
        }
        // draw the ruler
        Paint painter = new Paint();
        canvas.drawLine( BASE_X, BASE_Y, MAX_PIXEL_X - BASE_X, BASE_Y, painter );
        // from the strongest to weakest
        int times = keys.size();
        for( int i = 0 ; i < times; i++ ) {
          int key = keys.pollLast();
          ArrayList< RecordItem > list = recordsOnGraph.get( key );
          canvas.drawRect( BASE_X + MARGIN_X + i * ( width + gap ), BASE_Y, BASE_X + MARGIN_X + i * ( width + gap ) + width, BASE_Y + list.size() * amplifier, painter );
        }
      }
    }
  }
}
