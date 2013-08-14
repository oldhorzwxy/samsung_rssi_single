package cn.navior.tool.sumsungblerecsingle;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
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
  private StatTable totalStat;
  private TableLayout resultStat;
  // status
  private boolean clockEnabled;
  // tools
  private Thread clock;
  private PrintWriter writer;
  private PrintWriter statWriter;
  private Handler handler;
  private HashMap<Integer, ArrayList<RecordItem>> recordsOnGraph;
  private ArrayList<RecordItem> tempRecords;  // temporary record storage
  private int searchid;
  // fields about local Bluetooth device model
  private BluetoothAdapter mBluetoothAdapter; // local Bluetooth device model
  private BluetoothGatt mBluetoothGatt = null;    // local Bluetooth BLE device model
  private BluetoothGattCallback mGattCallbacks = new BluetoothGattCallback() {
    @Override
    public void onScanResult(final android.bluetooth.BluetoothDevice device, final int rssi, byte[] scanRecord) {
      // only deal with certain device
      if (device.getName().equals(nameInput.getEditableText().toString())) {
        // initialize the record
        RecordItem item = new RecordItem(device.getAddress());
        item.setRssi(rssi);  // replace the short value into an integer
        item.setName(device.getName());
        item.setDistance(Integer.parseInt(distanceInput.getEditableText().toString()));
        SimpleDateFormat tempDate = new SimpleDateFormat("kk-mm-ss-SS", Locale.ENGLISH);
        String datetime = tempDate.format(new java.util.Date());
        item.setDatetime(datetime);
        // put into HashMap
        if (recordsOnGraph.containsKey(rssi)) {
          recordsOnGraph.get(rssi).add(item);
        } else {
          ArrayList<RecordItem> list = new ArrayList<RecordItem>();
          list.add(item);
          recordsOnGraph.put(rssi, list);
        }
        // put into temporary storage
        tempRecords.add(item);

        // update searching status
        searchingStatus.post(new Runnable() {
          @Override
          public void run() {
            searchingStatus.setText("Found " + device.getName() + " with rssi " + rssi);
          }
        });

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
    searchid = 0;
    searchingStatus = (TextView) findViewById(R.id.searching_status);
    distanceInput = (EditText) findViewById(R.id.input_distence);
    nameInput = (EditText) findViewById(R.id.input_name);
    clockInput = (EditText) findViewById(R.id.input_clock);
    stopButton = (Button) findViewById(R.id.searching_stop);
    startButton = (Button) findViewById(R.id.searching_start);
    enableClockButton = (Button) findViewById(R.id.searching_enable_clock);
    quitButton = (Button) findViewById(R.id.searching_quit);
    clockEnabled = false;
    handler = new Handler();
    tempRecords = new ArrayList<RecordItem>();
    recordsOnGraph = new HashMap<Integer, ArrayList<RecordItem>>();
    totalStat = new StatTable(this);
    resultStat = new TableLayout(this);

    // build up local Bluetooth device model
    mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();  // get the Bluetooth adapter for this device
    if (mBluetoothGatt == null) {
      BluetoothGattAdapter.getProfileProxy(this, mProfileServiceListener, BluetoothGattAdapter.GATT);
    }

    // bind the action listeners
    // stop-searching button
    stopButton.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        // create the time string
        SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd-kk-mm-ss", Locale.ENGLISH);
        String datetime = tempDate.format(new java.util.Date());
        // close statistic writer
        statWriter.write("stop time,"+ datetime);
        statWriter.close();
        if (clock != null) {
          clock.interrupt();
          clock = null;
        }
        onStopScan();
      }
    });
    stopButton.setEnabled(false);  // disable the stop button until discovery starts
    // start-searching button
    startButton.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        // record the statistic results into a file
        // create directory
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "sumsung_rssirec_single_stat");
        if (!directory.exists()) {
          directory.mkdir();
        }

        // create the time string
        SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd-kk-mm-ss", Locale.ENGLISH);
        String datetime = tempDate.format(new java.util.Date());

        // create the file
        File recordFile = new File(directory.getAbsolutePath() + "/" + datetime + ".txt");
        if (recordFile.exists()) {
          recordFile.delete();
        }

        // open writer
        try {
          statWriter = new PrintWriter(recordFile);
          statWriter.write("Device name," + mBluetoothAdapter.getName() + "\n");
          statWriter.write("Device address," + mBluetoothAdapter.getAddress() + "\n");
          statWriter.write("Discovered device name," + nameInput.getEditableText().toString());
          statWriter.write("Discovered device distance," + distanceInput.getEditableText().toString());
          statWriter.write("clock," + clockInput.getEditableText().toString());
          statWriter.write("start," + datetime + "\n");
          statWriter.write("id,ave,2pk,ave2pk,str3,ave3,middle\n");
        } catch (FileNotFoundException e) {
          e.printStackTrace();
        }

        onStartScan();
      }
    });
    // enable-clock button
    enableClockButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        // if clock has been enabled, disable it
        if (clockEnabled) {
          clockEnabled = false;
          enableClockButton.setText("enable clock");
          clockInput.setEnabled(false);
        } else {
          clockEnabled = true;
          enableClockButton.setText("disable clock");
          clockInput.setEnabled(true);
        }
      }
    });
    // quit-searching button
    quitButton.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        SingleSearchingActivity.this.finish();
      }
    });

    // add the result statistic table
    ((LinearLayout) findViewById(R.id.searching_graph_board)).addView(totalStat);

    // add the graphs
    waveGraph = new WaveGraph(this);
    waveGraph.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500));
    ((LinearLayout) findViewById(R.id.searching_graph_board)).addView(waveGraph);
    distributionGraph = new DistributionGraph(this);
    distributionGraph.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500));
    ((LinearLayout) findViewById(R.id.searching_graph_board)).addView(distributionGraph);
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
    BluetoothGattAdapter.closeProfileProxy(BluetoothGattAdapter.GATT, mBluetoothGatt);
  }

  /**
   * logic on starting scanning
   */
  private void onStartScan() {
    searchid++;
    // clear the storage
    tempRecords.clear();
    recordsOnGraph.clear();
    resultStat.removeAllViews();
    // check input validity
    if (!checkInput()) {
      // notify user if invalid
      Toast.makeText(SingleSearchingActivity.this, "sth wrong in your input", Toast.LENGTH_SHORT).show();
      return;
    }
    // start only if distance and device name are set
    createPrintWriter();
    mBluetoothGatt.startScan();
    // if clock is enabled, start clock
    if (clockEnabled) {
      clock = new Thread() {
        @Override
        public void run() {
          try {
            Thread.sleep(Integer.parseInt(clockInput.getEditableText().toString()));
          } catch (InterruptedException e) {
            e.printStackTrace();
          } finally {
            handler.post(new Runnable() {
              @Override
              public void run() {
                // stop scan if time is up
                onStopScan();
              }
            });
          }
        }
      };
      clock.start();
    }
    // present the distance and name on status bar
    searchingStatus.setText("on discovering");
    // disable the buttons and input area
    stopButton.setEnabled(true);  // only enable stop button
    startButton.setEnabled(false);
    enableClockButton.setEnabled(false);
    nameInput.setEnabled(false);
    distanceInput.setEnabled(false);
    clockInput.setEnabled(false);
  }

  /**
   * check input validity
   *
   * @return
   */
  private boolean checkInput() {
    // check distance input
    if (distanceInput.getEditableText() == null
        || distanceInput.getEditableText().toString().equals("")) {
      return false;
    } else {
      try {
        Integer.parseInt(distanceInput.getEditableText().toString());
      } catch (NumberFormatException e) {
        return false;
      }
    }
    // check clock input
    if (clockEnabled) {
      if (clockInput.getEditableText() == null
          || clockInput.getEditableText().toString().equals("")) {
        return false;
      } else {
        try {
          Integer.parseInt(clockInput.getEditableText().toString());
        } catch (NumberFormatException e) {
          return false;
        }
      }
    }
    // check name input
    if (nameInput.getEditableText() == null
        || nameInput.getEditableText().toString().equals("")) {
      return false;
    }

    // all pass
    return true;
  }

  private void createPrintWriter() {
    // create directory
    File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "sumsung_rssirec_single");
    if (!directory.exists()) {
      directory.mkdir();
    }

    // create the time string
    SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd-kk-mm-ss", Locale.ENGLISH);
    String datetime = tempDate.format(new java.util.Date());

    // create the file
    File recordFile = new File(directory.getAbsolutePath() + "/" + datetime + ".txt");
    if (recordFile.exists()) {
      recordFile.delete();
    }

    // open writer
    try {
      writer = new PrintWriter(recordFile);
      writer.write("Device name," + mBluetoothAdapter.getName() + "\n");
      writer.write("Device address," + mBluetoothAdapter.getAddress() + "\n");
      writer.write("Discovered device name," + nameInput.getEditableText().toString() + "\n");
      writer.write("Discovered device distance," + distanceInput.getEditableText().toString() + "\n");
      writer.write("start," + datetime + "\n");
      writer.write("distance,rssi,time\n");
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
    searchingStatus.setText("Discovery has finished.");
    // show the statistic results
    showStatisticResults();
    // write records
    saveRecords();
    // repaint
    waveGraph.postInvalidate();
    distributionGraph.postInvalidate();
    // write stop info
    SimpleDateFormat tempDate = new SimpleDateFormat("kk-mm-ss-SS", Locale.ENGLISH);
    String datetime = tempDate.format(new java.util.Date());
    writer.write("stop," + datetime);
    writer.close();
    // enable the buttons
    stopButton.setEnabled(false);  // only disable stop button
    startButton.setEnabled(true);
    enableClockButton.setEnabled(true);
    nameInput.setEnabled(true);
    distanceInput.setEnabled(true);
    if (clockEnabled) {
      clockInput.setEnabled(true);
    }

    if( clock != null ) {
      onStartScan();
    }
  }

  private void showStatisticResults() {
    // get first half
    /*Iterator< ArrayList< RecordItem > > iterator = recordsOnGraph.values().iterator();
    int size = 0;
    while( iterator.hasNext() ) {
      size += iterator.next().size();
    }
    HashMap< Integer, ArrayList< RecordItem > > half = new HashMap<Integer, ArrayList<RecordItem> >();
    TreeSet<Integer> keys2 = new TreeSet<Integer>(recordsOnGraph.keySet());
    int max = keys2.pollLast();
    int halfValue = 0;
    while( half.values().size() + recordsOnGraph.get( max ).size() <= size / 2 ) {
      half.put( max, recordsOnGraph.get( max ) );
      halfValue += recordsOnGraph.get( max ).size();
      if( keys2.size() == 0 ) {
        break;
      }
      max = keys2.pollLast();
    }
    recordsOnGraph = half;*/

    // get first and last value
    TreeSet<Integer> keys = new TreeSet<Integer>(recordsOnGraph.keySet());
    if (keys.size() >= 2) {
      final int firstValue = keys.pollFirst();
      keys.add(firstValue);
      final int lastValue = keys.pollLast();
      keys.add(lastValue);
      // get distribution array
      ArrayList<Integer> sizeArray = new ArrayList<Integer>(lastValue - firstValue + 3);  // two items bigger than the value field
      for (int i = firstValue - 1; i <= lastValue + 1; i++) {
        if (recordsOnGraph.get(i) != null) {
          sizeArray.add(recordsOnGraph.get(i).size());
        } else {
          sizeArray.add(0); // if it's not in the distribution, it's size should be zero
        }
      }
      // get total record size
      //final int recordNum = tempRecords.size();
      // get overall average
      ArrayList<Integer> rssiList = new ArrayList<Integer>();
      for (int i = 0; i < tempRecords.size(); i++) {
        rssiList.add(tempRecords.get(i).getRssi());
      }
      final int average = (int) MyMathematicalMachine.getArithmeticAverage(rssiList);
      // get the standard derivation
      //double sd = MyMathematicalMachine.getStandardDeviation(rssiList);
      //sd = ((int) (sd * 100)) / 100.0;
      // get two peaks
      int firstPeak = 0;  // the weakest peak
      for (int i = 1; i <= lastValue - firstValue + 1; i++) {
        if (sizeArray.get(i) >= sizeArray.get(i - 1)
              && sizeArray.get(i) > sizeArray.get(i + 1)) {
            firstPeak = firstValue + (i - 1);
            break;
        }
      }
      int lastPeak = 0;  // the strongest peak
      boolean foundLastValley = false; // I'm looking for last peak which lies after last valley
      for (int i = lastValue - firstValue + 1; i >= 1; i--) {
        if( foundLastValley ) {
          if (sizeArray.get(i) >= sizeArray.get(i + 1)
              && sizeArray.get(i) > sizeArray.get(i - 1)) {
            lastPeak = firstValue + (i - 1);  // yes, it's right, using firstValue to compute lastPeak
            break;
          }
        }
        else {
          if( sizeArray.get( i ) <= sizeArray.get( i - 1 )
              && sizeArray.get( i ) <= sizeArray.get( i + 1 ) ) {
            foundLastValley = true;
          }
        }
      }
      // there is a special condition: the peaks group into one
      if (firstPeak > lastPeak) {
        int temp = firstPeak;
        firstPeak = lastPeak;
        lastPeak = temp;
      }
      // get the third peak
      int thirdPeak = lastPeak; // the third peak will be no later than last peak
      if (firstPeak != lastPeak) {
        for (int i = firstPeak - firstValue + 2; i <= lastPeak - firstValue + 1; i++) {
          if (sizeArray.get(i) >= sizeArray.get(i - 1)
              && sizeArray.get(i) > sizeArray.get(i + 1)) {
            thirdPeak = firstValue + (i - 1);
            break;
          }
        }
      }
      // get first three strongest
      int firstStrongest = 0;
      int secondStrongest = 0;
      int thirdStrongest = 0;
      int firstSIndex = 0;
      int secondSIndex = 0;
      int thirdSIndex = 0;
      for (int i = 1; i <= lastValue - firstValue + 1; i++) {
        // if found the strongest
        if (sizeArray.get(i) >= firstStrongest) {
          thirdStrongest = secondStrongest;
          secondStrongest = firstStrongest;
          firstStrongest = sizeArray.get(i);

          thirdSIndex = secondSIndex;
          secondSIndex = firstSIndex;
          firstSIndex = i;
        }
        // if found the second
        else if (sizeArray.get(i) >= secondStrongest) {
          thirdStrongest = secondStrongest;
          secondStrongest = sizeArray.get(i);

          thirdSIndex = secondSIndex;
          secondSIndex = i;
        }
        // if found the third
        else if (sizeArray.get(i) >= thirdStrongest) {
          thirdStrongest = sizeArray.get(i);
          thirdSIndex = i;
        }
      }
      firstStrongest = firstValue + firstSIndex - 1;
      secondStrongest = firstValue + secondSIndex - 1;
      thirdStrongest = firstValue + thirdSIndex - 1;
      // get average for first two peaks
      int ave2 = (lastPeak + thirdPeak) / 2;
      // get average for two strongest of the three most
      int ave3 = 0;
      if( firstStrongest <= secondStrongest && firstStrongest <= thirdStrongest ) {
        ave3 = ( secondStrongest + thirdStrongest ) / 2;
      }
      else if( secondStrongest <= firstStrongest && secondStrongest <= thirdStrongest ) {
        ave3 = ( firstStrongest + thirdStrongest ) / 2;
      }
      else {
        ave3 = ( firstStrongest + secondStrongest ) / 2;
      }
      // get the middle of the keys
      Iterator< Integer > iterator = keys.descendingIterator();
      int middle = 0;
      if( keys.size() % 2 != 0 ) {
        for( int i = 0; i < keys.size() / 2; i++ ) {
          iterator.next();
        }
        middle = iterator.next();
      }
      else {
        for( int i = 0; i < keys.size() / 2 - 1; i++ ) {
          iterator.next();
        }
        middle = ( iterator.next() + iterator.next() ) / 2;
      }
      // add the data into the table
      TableRow row = new TableRow(SingleSearchingActivity.this);
      totalStat.addBlock(searchid + "", row);
      totalStat.addBlock(average + "", row);
      totalStat.addBlock(lastPeak + ";" + thirdPeak + "", row);
      totalStat.addBlock(ave2 + "", row);
      totalStat.addBlock(firstStrongest + ";" + secondStrongest + ";" + thirdStrongest + "", row);
      totalStat.addBlock(ave3 + "", row);
      totalStat.addBlock(middle + "", row);
      //totalStat.addBlock(sd + "", row);
      totalStat.addView(row);

      statWriter.write("" + searchid + "," + average + "," + lastPeak + ";" + thirdPeak + "," + ave2 + "," + firstStrongest + ";" + secondStrongest
      + ";" + thirdStrongest + "," + ave3 + "," + middle + "\n" );
    }

    /*resultStat.post( new Runnable() {
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
    }*/
  }

  /**
   * Not handle NULLPOINTER exception for that writer is null
   */
  private void saveRecords() {
    for (int i = 0; i < tempRecords.size(); i++) {
      RecordItem item = tempRecords.get(i);
      writer.write(item.getDistance() + "," + item.getRssi() + "," + item.getDatetime() + "\n");
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

    WaveGraph(Context context) {
      super(context);

      gap = GAP;
      radius = RADIUS;
      amplifier = 50;
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      gap = GAP;
      radius = RADIUS;

      // ordered set, the lowest value comes first
      TreeSet<Integer> keys = new TreeSet<Integer>(recordsOnGraph.keySet());
      if (!keys.isEmpty() && keys.size() >= 2) {
        Paint painter = new Paint();
        int lastValue = keys.pollLast();
        keys.add(lastValue);
        int firstValue = keys.pollFirst();
        keys.add(firstValue);
        // adjust the height*
        this.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (lastValue - firstValue + 2) * amplifier));
        // adjust the gap and radius
        while (gap * tempRecords.size() + MARGIN_X + RULER_LENGTH >= MAX_PIXEL_X) {
          gap--;
        }  // too wide for the graph
        radius = (gap + 1) / 2 - 1;
        // draw the vertical rulers
        for (int i = 0; i < lastValue - firstValue + 1; i++) {
          canvas.drawLine(BASE_X, BASE_Y + i * amplifier, MAX_PIXEL_X - BASE_X, BASE_Y + i * amplifier, painter);
          canvas.drawText("" + (lastValue - i), BASE_X + 0.0f, BASE_Y + i * amplifier + 0.0f, painter);
        }
        // draw the horizontal rulers
        for (int i = 0; i < tempRecords.size(); i += 10) {
          canvas.drawLine(BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y, BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y + (lastValue - firstValue + 1) * amplifier, painter);
        }
        // draw the points
        for (int i = 0; i < tempRecords.size(); i++) {
          canvas.drawCircle(BASE_X + RULER_LENGTH + MARGIN_X + i * gap, BASE_Y + (-tempRecords.get(i).getRssi() + lastValue) * amplifier, radius, painter);
        }
      }
    }

  }

  class DistributionGraph extends View {

    private final static int BASE_X = 20;
    private final static int BASE_Y = 20;
    private final static int MARGIN_X = 50;
    private final static int GAP = 20;
    private final static int WIDTH = 50;
    private int gap;
    private int width;
    private int amplifier;

    DistributionGraph(Context context) {
      super(context);

      gap = 20;
      width = 50;
      amplifier = 20;
    }

    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      gap = GAP;
      width = WIDTH;
      // draw the graph
      TreeSet<Integer> keys = new TreeSet<Integer>(recordsOnGraph.keySet());
      if (!keys.isEmpty() && keys.size() >= 2) {
        // prepare the attributes
        Paint painter = new Paint();
        int lastValue = keys.pollLast();
        keys.add(lastValue);
        int firstValue = keys.pollFirst();
        keys.add(firstValue);
        // adjust the height
        int maxAmount = 0;
        Iterator<Integer> iterator = keys.descendingIterator();
        while (iterator.hasNext()) {
          int rssiValue = iterator.next();
          if (recordsOnGraph.get(rssiValue).size() > maxAmount) {
            maxAmount = recordsOnGraph.get(rssiValue).size();
          }
        }
        this.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (maxAmount + 1) * amplifier));
        // adjust gap and width
        while ((gap + width) * (lastValue - firstValue + 1) + 2 * MARGIN_X >= MAX_PIXEL_X) {
          gap /= 2;
          width /= 2;
        }
        // draw the ruler
        canvas.drawLine(BASE_X, BASE_Y, MAX_PIXEL_X - BASE_X, BASE_Y, painter);
        // from the strongest to weakest
        for (int i = 0; i < lastValue - firstValue + 1; i++) {
          canvas.drawText("" + (lastValue - i), BASE_X + i * (width + gap) + MARGIN_X, BASE_Y, painter); // draw the marker
          ArrayList<RecordItem> list = recordsOnGraph.get(lastValue - i);
          if (list != null) {
            canvas.drawRect(BASE_X + MARGIN_X + i * (width + gap), BASE_Y, BASE_X + MARGIN_X + i * (width + gap) + width, BASE_Y + list.size() * amplifier, painter);
          }
        }
      }
    }
  }

  class StatTable extends TableLayout {

    StatTable(Context context) {
      super(context);
      // set title bar
      TableRow titleBar = new TableRow(context);
      addBlock("id\t\t", titleBar);
      addBlock("ave\t\t", titleBar);
      addBlock("2pk\t\t\t\t\t\t", titleBar);
      addBlock("ave2pk\t\t", titleBar);
      addBlock("3str\t\t\t\t\t\t", titleBar);
      addBlock("ave3str\t\t", titleBar);
      addBlock("middle\t\t", titleBar);
      addView(titleBar);
    }

    private void addBlock(String s, TableRow row) {
      TextView t = new TextView(getContext());
      t.setText(s);
      row.addView(t);
    }
  }
}
