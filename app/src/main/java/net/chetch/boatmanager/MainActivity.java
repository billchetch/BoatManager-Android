package net.chetch.boatmanager;

import android.os.Bundle;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProviders;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import net.chetch.appframework.GenericActivity;
import net.chetch.appframework.IDialogManager;
import net.chetch.appframework.NotificationBar;
import net.chetch.cmalarms.AlarmPanelFragment;
import net.chetch.cmalarms.IAlarmPanelListener;
import net.chetch.cmalarms.data.Alarm;
import net.chetch.cmengineroom.data.Engine;
import net.chetch.messaging.ClientConnection;
import net.chetch.messaging.Message;
import net.chetch.messaging.MessagingViewModel;
import net.chetch.messaging.exceptions.MessagingServiceException;
import net.chetch.utilities.Logger;
import net.chetch.utilities.SLog;
import net.chetch.utilities.Utils;
import net.chetch.webservices.ConnectManager;
import net.chetch.webservices.Webservice;
import net.chetch.webservices.WebserviceViewModel;

import net.chetch.cmalarms.models.AlarmsMessageSchema;
import net.chetch.cmalarms.models.AlarmsMessagingModel;

import net.chetch.cmengineroom.models.EngineRoomMessageSchema;
import net.chetch.cmengineroom.models.EngineRoomMessagingModel;

import java.lang.reflect.Method;


public class MainActivity extends GenericActivity implements NotificationBar.INotifiable, IAlarmPanelListener {

    static boolean connected = false;
    static boolean suppressConnectionErrors = false;
    static ConnectManager connectManager = new ConnectManager();

    AlarmsMessagingModel aModel;
    MutableLiveData<Alarm> alarmLiveData = new MutableLiveData<>();
    EngineRoomMessagingModel erModel;

    ViewPager2 viewPager;

    Observer connectProgress  = obj -> {
        showProgress();
        if(obj instanceof WebserviceViewModel.LoadProgress) {
            //do nothing
        } else if(obj instanceof ClientConnection){
            //do nothing
        } else if(obj instanceof ConnectManager) {
            //ConnectManager cm = (ConnectManager) obj;
            //do nothing
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        includeActionBar(SettingsActivity.class);

        MainViewPagerAdapter mainViewPagerAdapater = new MainViewPagerAdapter(this);
        viewPager = findViewById(R.id.mainViewPager);
        viewPager.setAdapter(mainViewPagerAdapater);


        TabLayout tabLayout = findViewById(R.id.mainTabLayout);
        String[] labels = new String[]{"Alarms", "Engines", "Pumps", "Water Tanks"};
        TabLayoutMediator tabLayoutMediator = new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> tab.setText(labels[position]));
        tabLayoutMediator.attach();

        NotificationBar.setView(findViewById(R.id.notificationbar), 100);

        aModel = ViewModelProviders.of(this).get(AlarmsMessagingModel.class);
        erModel = ViewModelProviders.of(this).get(EngineRoomMessagingModel.class);

        if(!connectManager.isConnected()) {
            aModel.getError().observe(this, throwable -> {
                try {
                    handleError(throwable, aModel);
                } catch (Exception e){
                    SLog.e("Main", e.getMessage());
                }
            });

            erModel.getError().observe(this, throwable -> {
                try {
                    handleError(throwable, erModel);
                } catch (Exception e){
                    SLog.e("Main", e.getMessage());
                }
            });


            try {
                Logger.info("Main activity setting cm client names, adding modules and requesting connect ...");
                aModel.setClientName("BMCMCAlarms", getApplicationContext());
                erModel.setClientName("BMCMCEngineRoom", getApplicationContext());

                connectManager.addModel(aModel);
                connectManager.addModel(erModel);

                connectManager.setPermissableServerTimeDifference(5 * 60);
                connectManager.requestConnect(connectProgress);

                //NotificationBar.monitor(this, connectManager, "connection");
                //NotificationBar.monitor(this, alarmLiveData, "alarm");
                //NotificationBar.monitor(this, erModel.dataEvent, "engine room data event");

            } catch (Exception e) {
                showError(e);
            }
        } else {
            //already connected so ensure things are hidden that might otherwise be displayed by default
            hideProgress();
            NotificationBar.hide();
            findViewById(R.id.mainBody).setVisibility(View.VISIBLE);
        }
    }

    public AlarmPanelFragment createAlarmPanelFragment(){
        AlarmPanelFragment alarmPanelFragment = new AlarmPanelFragment();
        alarmPanelFragment.listener = this;
        alarmPanelFragment.horizontal = false;
        return alarmPanelFragment;
    }
    private String getStackTrace(Throwable t){
        String stackTrace = "";
        StackTraceElement[] st = t.getStackTrace();
        for(StackTraceElement ste : st){
            String s = ste.getFileName() + " @ " + ste.getLineNumber() + " in " + ste.getMethodName();
            stackTrace += s + "\n";
        }
        return stackTrace;
    }

    private void handleError(Throwable t, Object source){

        String errMsg = "SCE: " + suppressConnectionErrors + ", CNCT: " + connected + ", ICE: " + ConnectManager.isConnectionError(t);
        errMsg += "\n" + t.getClass().getName() + "\n" + t.getMessage() + "\n" + t.getCause() + "\n" + getStackTrace(t);
        SLog.e("MAIN", errMsg);
    }

    @Override
    public void openAbout() {
        super.openAbout();
        try {
            String lf = "\n";
            BMApplication app = (BMApplication)getApplication();

            String s = "";
            s += "App uptime: " + Utils.formatDuration(app.getUpTime(), Utils.DurationFormat.DAYS_HOURS_MINS_SECS) + lf;

            ClientConnection client = aModel.getClient();
            s += client.getName() + " is of state " + client.getState() + lf;
            MessagingViewModel.MessagingService messagingService = aModel.getMessaingService(AlarmsMessagingModel.SERVICE_NAME);
            s += messagingService.name + " service is of state " + messagingService.state + lf;
            s += "Last message received on: " + Utils.formatDate(messagingService.lastMessageReceivedOn, Webservice.DEFAULT_DATE_FORMAT);

            s+= lf;

            client = erModel.getClient();
            s += client.getName() + " is of state " + client.getState() + lf;
            messagingService = erModel.getMessaingService(EngineRoomMessagingModel.SERVICE_NAME);
            s += messagingService.name + " service is of state " + messagingService.state + lf;
            s += "Last message received on: " + Utils.formatDate(messagingService.lastMessageReceivedOn, Webservice.DEFAULT_DATE_FORMAT);

            aboutDialog.aboutBlurb = s;

        } catch (Exception e){

        }
    }

    @Override
    public void handleNotification(Object notifier, String tag, Object data) {
        if(notifier instanceof ConnectManager){
            ConnectManager cm = (ConnectManager)notifier;
            switch(cm.getState()){
                case CONNECTED:
                    NotificationBar.show(NotificationBar.NotificationType.INFO, "Connected and ready to use.", null,5);
                    break;

                case ERROR:
                    NotificationBar.show(NotificationBar.NotificationType.ERROR, "Service unavailable.");
                    break;

                case RECONNECT_REQUEST:
                    NotificationBar.show(NotificationBar.NotificationType.WARNING, "Attempting to reconnect...");
                    break;
            }
        } else if (notifier instanceof MutableLiveData) {
            switch(tag){
                case "alarm":
                    Alarm alarm = (Alarm)data;
                    String msg = null;
                    switch(alarm.getAlarmState()){
                        case LOWERED:
                        case DISABLED:
                            NotificationBar.hide();
                            break;
                        default:
                            if(viewPager.getCurrentItem() > 0) { //i.e. we are not on alarms page
                                msg = "Alarm " + alarm.getName() + " is of state " + alarm.getAlarmState();
                                NotificationBar.show(NotificationBar.NotificationType.ALERT, msg);
                            }
                            break;
                    }
                    break;
            }
        }
    }

    public void onCreateAlarmPanel(AlarmPanelFragment fragment){
        fragment.horizontal = false;
    }

    @Override
    public void onAlarmStateChange(Alarm alarm, AlarmsMessageSchema.AlarmState newState, AlarmsMessageSchema.AlarmState oldState) {
        alarmLiveData.setValue(alarm);
    }

    @Override
    public void onViewAlarmsLog(Alarm alarm) {

    }

    @Override
    public void onSilenceAlarmBuzzer(int duration) {

    }

    @Override
    public void onDisableAlarm(Alarm alarm) {
        showConfirmationDialog("Are you sure you want to disable " + alarm.getName() + "?", (dialog, which)->{
            aModel.disableAlarm(alarm.getAlarmID());
        });
    }
}
