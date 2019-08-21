package com.example.mygcs;

import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.LocationTrackingMode;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.UiSettings;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.OverlayImage;
import com.naver.maps.map.overlay.PolygonOverlay;
import com.naver.maps.map.overlay.PolylineOverlay;
import com.naver.maps.map.util.FusedLocationSource;
import com.o3dr.android.client.ControlTower;
import com.o3dr.android.client.Drone;
import com.o3dr.android.client.apis.ControlApi;
import com.o3dr.android.client.apis.MissionApi;
import com.o3dr.android.client.apis.VehicleApi;
import com.o3dr.android.client.interfaces.DroneListener;
import com.o3dr.android.client.interfaces.LinkListener;
import com.o3dr.android.client.interfaces.TowerListener;
import com.o3dr.services.android.lib.coordinate.LatLong;
import com.o3dr.services.android.lib.coordinate.LatLongAlt;
import com.o3dr.services.android.lib.drone.attribute.AttributeEvent;
import com.o3dr.services.android.lib.drone.attribute.AttributeType;
import com.o3dr.services.android.lib.drone.connection.ConnectionParameter;
import com.o3dr.services.android.lib.drone.mission.Mission;
import com.o3dr.services.android.lib.drone.mission.item.spatial.Waypoint;
import com.o3dr.services.android.lib.drone.property.Altitude;
import com.o3dr.services.android.lib.drone.property.Attitude;
import com.o3dr.services.android.lib.drone.property.Battery;
import com.o3dr.services.android.lib.drone.property.Gps;
import com.o3dr.services.android.lib.drone.property.Speed;
import com.o3dr.services.android.lib.drone.property.State;
import com.o3dr.services.android.lib.drone.property.Type;
import com.o3dr.services.android.lib.drone.property.VehicleMode;
import com.o3dr.services.android.lib.gcs.link.LinkConnectionStatus;
import com.o3dr.services.android.lib.model.AbstractCommandListener;
import com.o3dr.services.android.lib.model.SimpleCommandListener;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements DroneListener, TowerListener, LinkListener, OnMapReadyCallback {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;
    private FusedLocationSource locationSource;

    MapFragment mNaverMapFragment = null;

    private Drone drone;

    NaverMap naverMap;

    List<Marker> markers = new ArrayList<>();
    List<LatLng> coords = new ArrayList<>(); // 폴리라인
    ArrayList<String> recycler_list = new ArrayList<>(); // 리사이클러뷰
    List<LocalTime> recycler_time = new ArrayList<>(); // 리사이클러뷰 시간
    List<Marker> Auto_Marker = new ArrayList<>();       // 간격감시 마커
    LatLng[] Gap_LatLng = new LatLng[4];                // 간격감시 폴리곤
    List<LatLng> Auto_Polyline = new ArrayList<>();     // 간격감시 폴리라인

    Marker marker_goal = new Marker(); // Guided 모드 마커

    PolylineOverlay polyline = new PolylineOverlay();
    PolygonOverlay polygon = new PolygonOverlay();
    PolylineOverlay polylinePath = new PolylineOverlay();

    private int droneType = Type.TYPE_UNKNOWN;
    private ControlTower controlTower;

    private Spinner modeSelector;

    private static final int DEFAULT_UDP_PORT = 14550;
    private static final int DEFAULT_USB_BAUD_RATE = 57600;

    private int Marker_Count = 0;
    private int Recycler_Count = 0;
    private int takeOffAltitude = 3;
    private int Auto_Marker_Count = 0;
    public int Auto_Distance = 50;
    public int Gap_Distance = 5;
    private int Gap_Top = 0;

    protected double mRecentAltitude = 0;

    public int Reached_Count = 0;

    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) { // 맵 실행 되기 전
        Log.i(TAG, "Start mainActivity");
        super.onCreate(savedInstanceState);
        // 소프트바 없애기
        deleteStatusBar();
        // 상태바 없애기
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        final Context context = getApplicationContext();
        this.controlTower = new ControlTower(context);
        this.drone = new Drone(context);

        // 지도 띄우기
        FragmentManager fm = getSupportFragmentManager();
        mNaverMapFragment = (MapFragment) fm.findFragmentById(R.id.map);
        if (mNaverMapFragment == null) {
            mNaverMapFragment = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map, mNaverMapFragment).commit();
        }

        // 모드 변경 스피너
        this.modeSelector = (Spinner) findViewById(R.id.modeSelect);
        this.modeSelector.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                ((TextView) parent.getChildAt(0)).setTextColor(Color.WHITE);
                onFlightModeSelected(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> prent) {
                // Do nothing
            }
        });

        // 내 위치
        locationSource = new FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE);

        mNaverMapFragment.getMapAsync(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (locationSource.onRequestPermissionsResult(
                requestCode, permissions, grantResults)) {
            return;
        }
        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);
    }

    @Override
    public void onMapReady(@NonNull NaverMap naverMap) {
        // onMapReady는 지도가 불러와지면 그때 한번 실행
        this.naverMap = naverMap;

        // 켜지자마자 드론 연결
        ConnectionParameter params = ConnectionParameter.newUdpConnection(null);
        this.drone.connect(params);

        // 네이버 로고 위치 변경
        UiSettings uiSettings = naverMap.getUiSettings();
        uiSettings.setLogoMargin(2080, 0, 0, 925);

        // 축척 바 제거
        uiSettings.setScaleBarEnabled(false);

        // 줌 버튼 제거
        uiSettings.setZoomControlEnabled(false);

        // 초기 상태를 맵 잠금으로 설정
        uiSettings.setScrollGesturesEnabled(false);

        // UI상 버튼 제어
        ControlButton();

        // 내 위치
        naverMap.setLocationSource(locationSource);
        naverMap.setLocationTrackingMode(LocationTrackingMode.NoFollow);

        // 롱 클릭 시 경고창
        naverMap.setOnMapLongClickListener(new NaverMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(@NonNull PointF pointF, @NonNull LatLng coord) {
                LongClickWarning(pointF, coord);
            }
        });
    }

    private void ShowSatelliteCount() {
        // [UI] 잡히는 GPS 개수
        Gps droneGps = this.drone.getAttribute(AttributeType.GPS);
        int Satellite = droneGps.getSatellitesCount();
        TextView textView_gps = (TextView) findViewById(R.id.GPS_state);
        textView_gps.setText("위성 " + Satellite);
    }

    private void ShowTakeOffAltitude() {
        final Button BtnTakeOffAltitude = (Button) findViewById(R.id.BtnTakeOffAltitude);
        BtnTakeOffAltitude.setText(getTakeOffAltitude() + " m\n이륙고도");
    }

    // 이륙고도 get, set 함수
    public int getTakeOffAltitude() {
        return this.takeOffAltitude;
    }

    public void setTakeOffAltitude(int Altitude) {
        this.takeOffAltitude = Altitude;
    }

    // ############################ 롱클릭 시 Guided 모드로 변경 ###################################
    private void LongClickWarning(@NonNull PointF pointF, @NonNull final LatLng coord) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("가이드 모드");
        builder.setMessage("클릭한 지점으로 이동하게 됩니다. 이동하시겠습니까?");
        builder.setPositiveButton("예", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // 도착지 마커 생성
                marker_goal.setMap(null);
                marker_goal.setPosition(new LatLng(coord.latitude, coord.longitude));
                marker_goal.setIcon(OverlayImage.fromResource(R.drawable.final_flag));
                marker_goal.setWidth(70);
                marker_goal.setHeight(70);
                marker_goal.setMap(naverMap);

                // Guided 모드로 변환
                ChangeToGuidedMode();

                // 지정된 위치로 이동
                GotoTartget();
            }
        });
        builder.setNegativeButton("아니오", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    private void ChangeToGuidedMode() {
        VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_GUIDED, new SimpleCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("가이드 모드로 변경합니다.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("가이드 모드로 변경하는데 실패하였습니다. : " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("가이드 모드로 변경하는데 실패하였습니다.");
            }
        });
    }

    private void GotoTartget() {
        ControlApi.getApi(this.drone).goTo(
                new LatLong(marker_goal.getPosition().latitude, marker_goal.getPosition().longitude),
                true, new AbstractCommandListener() {
                    @Override
                    public void onSuccess() {
                        alertUser("목적지로 향합니다.");
                    }

                    @Override
                    public void onError(int executionError) {
                        alertUser("이동 할 수 없습니다 : " + executionError);
                    }

                    @Override
                    public void onTimeout() {
                        alertUser("이동 할 수 없습니다.");
                    }
                });
    }

    // #############################################################################################
    public void ControlButton() {
        // 기본 UI 4개 버튼
        final Button BtnMapMoveLock = (Button) findViewById(R.id.BtnMapMoveLock);
        final Button BtnMapType = (Button) findViewById(R.id.BtnMapType);
        final Button BtnLandRegistrationMap = (Button) findViewById(R.id.BtnLandRegistrationMap);
        final Button BtnClear = (Button) findViewById(R.id.BtnClear);
        // Map 잠금 버튼
        final Button MapMoveLock = (Button) findViewById(R.id.MapMoveLock);
        final Button MapMoveUnLock = (Button) findViewById(R.id.MapMoveUnLock);
        // Map Type 버튼
        final Button MapType_Basic = (Button) findViewById(R.id.MapType_Basic);
        final Button MapType_Terrain = (Button) findViewById(R.id.MapType_Terrain);
        final Button MapType_Satellite = (Button) findViewById(R.id.MapType_Satellite);
        // 지적도 버튼
        final Button LandRegistrationOn = (Button) findViewById(R.id.LandRegistrationOn);
        final Button LandRegistrationOff = (Button) findViewById(R.id.LandRegistrationOff);
        // 이륙고도 버튼
        final Button BtnTakeOffAltitude = (Button) findViewById(R.id.BtnTakeOffAltitude);
        // 이륙고도 Up / Down 버튼
        final Button TakeOffUp = (Button) findViewById(R.id.TakeOffUp);
        final Button TakeOffDown = (Button) findViewById(R.id.TakeOffDown);
        // 비행 모드 버튼
        final Button BtnFlightMode = (Button) findViewById(R.id.BtnFlightMode);
        // 비행 모드 설정 버튼
        final Button FlightMode_Basic = (Button) findViewById(R.id.FlightMode_Basic);
        final Button FlightMode_Path = (Button) findViewById(R.id.FlightMode_Path);
        final Button FlightMode_Gap = (Button) findViewById(R.id.FlightMode_Gap);
        final Button FlightMode_Area = (Button) findViewById(R.id.FlightMode_Area);

        // 임무 전송 / 임무 시작/ 임무 중지
        final Button BtnSendMission = (Button) findViewById(R.id.BtnSendMission);

        final UiSettings uiSettings = naverMap.getUiSettings();

        // ############################## 기본 UI 버튼 제어 #######################################
        // 맵 이동 / 맵 잠금
        BtnMapMoveLock.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 열려있으면 닫기
                if (MapType_Satellite.getVisibility() == view.VISIBLE) {
                    MapType_Basic.setVisibility(View.INVISIBLE);
                    MapType_Terrain.setVisibility(View.INVISIBLE);
                    MapType_Satellite.setVisibility(View.INVISIBLE);
                }
                // 열려있으면 닫기
                if (LandRegistrationOn.getVisibility() == view.VISIBLE) {
                    LandRegistrationOn.setVisibility(View.INVISIBLE);
                    LandRegistrationOff.setVisibility(View.INVISIBLE);
                }

                if (MapMoveLock.getVisibility() == view.INVISIBLE) {
                    MapMoveLock.setVisibility(View.VISIBLE);
                    MapMoveUnLock.setVisibility(View.VISIBLE);
                } else if (MapMoveLock.getVisibility() == view.VISIBLE) {
                    MapMoveLock.setVisibility(View.INVISIBLE);
                    MapMoveUnLock.setVisibility(View.INVISIBLE);
                }
            }
        });

        // 지도 모드
        BtnMapType.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 열려있으면 닫기
                if (MapMoveUnLock.getVisibility() == view.VISIBLE) {
                    MapMoveUnLock.setVisibility(View.INVISIBLE);
                    MapMoveLock.setVisibility(View.INVISIBLE);
                }
                // 열려있으면 닫기
                if (LandRegistrationOn.getVisibility() == view.VISIBLE) {
                    LandRegistrationOn.setVisibility(View.INVISIBLE);
                    LandRegistrationOff.setVisibility(View.INVISIBLE);
                }
                if (MapType_Satellite.getVisibility() == view.INVISIBLE) {
                    MapType_Satellite.setVisibility(View.VISIBLE);
                    MapType_Terrain.setVisibility(View.VISIBLE);
                    MapType_Basic.setVisibility(View.VISIBLE);
                } else {
                    MapType_Satellite.setVisibility(View.INVISIBLE);
                    MapType_Terrain.setVisibility(View.INVISIBLE);
                    MapType_Basic.setVisibility(View.INVISIBLE);
                }
            }
        });

        // 지적도
        BtnLandRegistrationMap.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 열려있으면 닫기
                if (MapType_Satellite.getVisibility() == view.VISIBLE) {
                    MapType_Basic.setVisibility(View.INVISIBLE);
                    MapType_Terrain.setVisibility(View.INVISIBLE);
                    MapType_Satellite.setVisibility(View.INVISIBLE);
                }
                // 열려있으면 닫기
                if (MapMoveUnLock.getVisibility() == view.VISIBLE) {
                    MapMoveUnLock.setVisibility(View.INVISIBLE);
                    MapMoveLock.setVisibility(View.INVISIBLE);
                }

                if (LandRegistrationOff.getVisibility() == view.INVISIBLE) {
                    LandRegistrationOff.setVisibility(View.VISIBLE);
                    LandRegistrationOn.setVisibility(View.VISIBLE);
                } else {
                    LandRegistrationOff.setVisibility(View.INVISIBLE);
                    LandRegistrationOn.setVisibility(View.INVISIBLE);
                }
            }
        });

        // ############################### 맵 이동 관련 제어 ######################################
        // 맵잠금
        MapMoveLock.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                MapMoveUnLock.setBackgroundResource(R.drawable.mybutton_dark);
                MapMoveLock.setBackgroundResource(R.drawable.mybutton);

                BtnMapMoveLock.setText("맵 잠금");

                uiSettings.setScrollGesturesEnabled(false);

                MapMoveLock.setVisibility(View.INVISIBLE);
                MapMoveUnLock.setVisibility(View.INVISIBLE);
            }
        });

        // 맵 이동
        MapMoveUnLock.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                MapMoveUnLock.setBackgroundResource(R.drawable.mybutton);
                MapMoveLock.setBackgroundResource(R.drawable.mybutton_dark);

                BtnMapMoveLock.setText("맵 이동");

                uiSettings.setScrollGesturesEnabled(true);

                MapMoveLock.setVisibility(View.INVISIBLE);
                MapMoveUnLock.setVisibility(View.INVISIBLE);
            }
        });

        // ################################## 지도 모드 제어 ######################################

        // 위성 지도
        MapType_Satellite.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 색 지정
                MapType_Satellite.setBackgroundResource(R.drawable.mybutton);
                MapType_Basic.setBackgroundResource(R.drawable.mybutton_dark);
                MapType_Terrain.setBackgroundResource(R.drawable.mybutton_dark);

                BtnMapType.setText("위성지도");

                naverMap.setMapType(NaverMap.MapType.Satellite);

                // 다시 닫기
                MapType_Satellite.setVisibility(View.INVISIBLE);
                MapType_Terrain.setVisibility(View.INVISIBLE);
                MapType_Basic.setVisibility(View.INVISIBLE);
            }
        });

        // 지형도
        MapType_Terrain.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 색 지정
                MapType_Satellite.setBackgroundResource(R.drawable.mybutton_dark);
                MapType_Basic.setBackgroundResource(R.drawable.mybutton_dark);
                MapType_Terrain.setBackgroundResource(R.drawable.mybutton);

                BtnMapType.setText("지형도");

                naverMap.setMapType(NaverMap.MapType.Terrain);

                MapType_Satellite.setVisibility(View.INVISIBLE);
                MapType_Terrain.setVisibility(View.INVISIBLE);
                MapType_Basic.setVisibility(View.INVISIBLE);
            }
        });

        // 일반지도
        MapType_Basic.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                MapType_Satellite.setBackgroundResource(R.drawable.mybutton_dark);
                MapType_Basic.setBackgroundResource(R.drawable.mybutton);
                MapType_Terrain.setBackgroundResource(R.drawable.mybutton_dark);

                BtnMapType.setText("일반지도");

                naverMap.setMapType(NaverMap.MapType.Basic);

                MapType_Satellite.setVisibility(View.INVISIBLE);
                MapType_Terrain.setVisibility(View.INVISIBLE);
                MapType_Basic.setVisibility(View.INVISIBLE);
            }
        });

        // ################################ 지적도 On / Off 제어 ##################################

        LandRegistrationOn.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                LandRegistrationOn.setBackgroundResource(R.drawable.mybutton);
                LandRegistrationOff.setBackgroundResource(R.drawable.mybutton_dark);

                BtnLandRegistrationMap.setText("지적도 ON");

                naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_CADASTRAL, true);

                LandRegistrationOn.setVisibility(View.INVISIBLE);
                LandRegistrationOff.setVisibility(View.INVISIBLE);
            }
        });

        LandRegistrationOff.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                LandRegistrationOn.setBackgroundResource(R.drawable.mybutton_dark);
                LandRegistrationOff.setBackgroundResource(R.drawable.mybutton);

                BtnLandRegistrationMap.setText("지적도 OFF");

                naverMap.setLayerGroupEnabled(NaverMap.LAYER_GROUP_CADASTRAL, false);

                LandRegistrationOn.setVisibility(View.INVISIBLE);
                LandRegistrationOff.setVisibility(View.INVISIBLE);
            }
        });

        // ###################################### Clear ###########################################
        BtnClear.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 열려있으면 닫기
                if (MapMoveUnLock.getVisibility() == view.VISIBLE) {
                    MapMoveUnLock.setVisibility(View.INVISIBLE);
                    MapMoveLock.setVisibility(View.INVISIBLE);
                }
                // 열려있으면 닫기
                if (MapType_Satellite.getVisibility() == view.VISIBLE) {
                    MapType_Basic.setVisibility(View.INVISIBLE);
                    MapType_Terrain.setVisibility(View.INVISIBLE);
                    MapType_Satellite.setVisibility(View.INVISIBLE);
                }
                // 열려있으면 닫기
                if (LandRegistrationOn.getVisibility() == view.VISIBLE) {
                    LandRegistrationOn.setVisibility(View.INVISIBLE);
                    LandRegistrationOff.setVisibility(View.INVISIBLE);
                }

                // 이전 마커 지우기
                if (Marker_Count - 1 >= 0) {
                    markers.get(Marker_Count - 1).setMap(null);
                }

                // 폴리라인 지우기
                polyline.setMap(null);
                polygon.setMap(null);
                polylinePath.setMap(null);

                // Auto_Marker 지우기
                if(Auto_Marker.size() != 0) {
                    Auto_Marker.get(0).setMap(null);
                    Auto_Marker.get(1).setMap(null);
                }

                // 리스트 값 지우기
                coords.clear();
                Auto_Marker.clear();
                Auto_Polyline.clear();

                // ArrayList 값 지우기
                for(int i=0;i<Gap_LatLng.length;i++)
                {
                    Gap_LatLng[i]=null;
                }

                // Top 변수 초기화
                Marker_Count=0;
                Auto_Marker_Count=0;
                Gap_Top=0;

                Reached_Count=0;
            }
        });

        // ###################################### 이륙 고도 설정 #################################
        // 이륙고도 버튼
        BtnTakeOffAltitude.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 열려있으면 닫기
                if (TakeOffUp.getVisibility() == view.VISIBLE) {
                    TakeOffUp.setVisibility(View.INVISIBLE);
                    TakeOffDown.setVisibility(View.INVISIBLE);
                } else if (TakeOffUp.getVisibility() == view.INVISIBLE) {
                    TakeOffUp.setVisibility(View.VISIBLE);
                    TakeOffDown.setVisibility(View.VISIBLE);
                }
            }
        });

        // 이륙고도 Up 버튼
        TakeOffUp.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                SetTakeOffAltitudeUp();
                ShowTakeOffAltitude();
            }
        });

        // 이륙고도 Down 버튼
        TakeOffDown.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                SetTakeOffAltitudeDown();
                ShowTakeOffAltitude();
            }
        });

        // #################################### 비행 모드 설정 ####################################
        // 비행 모드 버튼
        BtnFlightMode.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (FlightMode_Basic.getVisibility() == view.VISIBLE) {
                    FlightMode_Basic.setVisibility(view.INVISIBLE);
                    FlightMode_Path.setVisibility(view.INVISIBLE);
                    FlightMode_Gap.setVisibility(view.INVISIBLE);
                    FlightMode_Area.setVisibility(view.INVISIBLE);
                } else if (FlightMode_Basic.getVisibility() == view.INVISIBLE) {
                    FlightMode_Basic.setVisibility(view.VISIBLE);
                    FlightMode_Path.setVisibility(view.VISIBLE);
                    FlightMode_Gap.setVisibility(view.VISIBLE);
                    FlightMode_Area.setVisibility(view.VISIBLE);
                }
            }
        });

        // 일반 모드
        FlightMode_Basic.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO : Basic FlightMode event
                BtnFlightMode.setText("일반\n모드");

                BtnSendMission.setVisibility(view.INVISIBLE);

                FlightMode_Basic.setVisibility(view.INVISIBLE);
                FlightMode_Path.setVisibility(view.INVISIBLE);
                FlightMode_Gap.setVisibility(view.INVISIBLE);
                FlightMode_Area.setVisibility(view.INVISIBLE);
            }
        });

        // 경로 비행 모드
        FlightMode_Path.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO : Path FlgithMode event
                BtnFlightMode.setText("경로\n비행");

                FlightMode_Basic.setVisibility(view.INVISIBLE);
                FlightMode_Path.setVisibility(view.INVISIBLE);
                FlightMode_Gap.setVisibility(view.INVISIBLE);
                FlightMode_Area.setVisibility(view.INVISIBLE);
            }
        });

        // 간격 감시 모드
        FlightMode_Gap.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO : Gap FlightMode event
                BtnFlightMode.setText("간격\n감시");

                BtnSendMission.setVisibility(View.VISIBLE);

                // 두 지점 + 폴리곤 생성
                MakePolygon();

                FlightMode_Basic.setVisibility(view.INVISIBLE);
                FlightMode_Path.setVisibility(view.INVISIBLE);
                FlightMode_Gap.setVisibility(view.INVISIBLE);
                FlightMode_Area.setVisibility(view.INVISIBLE);
            }
        });

        // 면적 감시 모드
        FlightMode_Area.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO : Area FlightMode event
                BtnFlightMode.setText("면적\n감시");

                FlightMode_Basic.setVisibility(view.INVISIBLE);
                FlightMode_Path.setVisibility(view.INVISIBLE);
                FlightMode_Gap.setVisibility(view.INVISIBLE);
                FlightMode_Area.setVisibility(view.INVISIBLE);
            }
        });
    }

    private void MakeWayPoint() {
        final Mission mMission = new Mission();

        for(int i=0;i<Auto_Polyline.size();i++) {
            Waypoint waypoint = new Waypoint();
            waypoint.setDelay(1);

            Altitude altitude = this.drone.getAttribute(AttributeType.ALTITUDE);
            LatLongAlt latLongAlt = new LatLongAlt(Auto_Polyline.get(i).latitude, Auto_Polyline.get(i).longitude, altitude.getAltitude());
            waypoint.setCoordinate(latLongAlt);

            mMission.addMissionItem(waypoint);
        }

        final Button BtnSendMission = (Button) findViewById(R.id.BtnSendMission);
        BtnSendMission.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(BtnSendMission.getText().equals("임무 전송")) {
                    if(Gap_LatLng[1] != null) {
                        setMission(mMission);
                    } else {
                        alertUser("A,B좌표 필요");
                    }
                }
                else if(BtnSendMission.getText().equals("임무 시작")) {
                    // Auto모드로 전환
                    ChangeToAutoMode();
                    BtnSendMission.setText("임무 중지");
                }
                else if(BtnSendMission.getText().equals("임무 중지")) {
                    pauseMission();
                    ChangeToLoiterMode();
                    BtnSendMission.setText("임무 전송");
                }
            }
        });
    }

    private void setMission(Mission mMission) {
        MissionApi.getApi(this.drone).setMission(mMission,true);
    }

    private void pauseMission() {
        MissionApi.getApi(this.drone).pauseMission(null);
    }

    private void ChangeToLoiterMode() {
        VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LOITER, new SimpleCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("Loiter 모드로 변경합니다.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("Loiter 모드 변경 실패 : " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("Loiter 모드 변경 실패");
            }
        });
    }

    private void ChangeToAutoMode() {
        VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_AUTO, new SimpleCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("Auto 모드로 변경합니다.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("Auto 모드로 변경하는데 실패하였습니다. : " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("Auto 모드로 변경하는데 실패하였습니다.");
            }
        });
    }

    private void MakePolygon() {
        alertUser("A와 B좌표를 클릭하세요.");
        naverMap.setOnMapClickListener(new NaverMap.OnMapClickListener() {
            @Override
            public void onMapClick(@NonNull PointF pointF, @NonNull LatLng latLng) {
                if (Gap_Top < 2) {
                    Marker marker = new Marker();
                    marker.setPosition(latLng);
                    Gap_LatLng[Gap_Top] = latLng;

                    // Auto_Marker에 넣기 위해 marker 생성..
                    Auto_Marker.add(marker);
                    Auto_Marker.get(Auto_Marker_Count).setMap(naverMap);

                    if(Gap_Top == 0) {
                        Auto_Marker.get(0).setIcon(OverlayImage.fromResource(R.drawable.number1));
                        Auto_Marker.get(0).setWidth(80);
                        Auto_Marker.get(0).setHeight(80);
                        Auto_Marker.get(0).setAnchor(new PointF(0.5F, 0.5F));
                    } else if(Gap_Top == 1) {
                        Auto_Marker.get(1).setIcon(OverlayImage.fromResource(R.drawable.number2));
                        Auto_Marker.get(1).setWidth(80);
                        Auto_Marker.get(1).setHeight(80);
                        Auto_Marker.get(1).setAnchor(new PointF(0.5F, 0.5F));
                    }

                    Gap_Top++;
                    Auto_Marker_Count++;
                }
                if(Auto_Marker_Count == 2) {
                    double heading = MyUtil.computeHeading(Auto_Marker.get(0).getPosition(),Auto_Marker.get(1).getPosition());

                    LatLng latLng1 = MyUtil.computeOffset(Auto_Marker.get(1).getPosition(), Auto_Distance, heading+90);
                    LatLng latLng2 = MyUtil.computeOffset(Auto_Marker.get(0).getPosition(), Auto_Distance, heading+90);

                    // ############################################################################
                    Gap_LatLng[2]=latLng1;
                    Gap_LatLng[3]=latLng2;
                    polygon.setCoords(Arrays.asList(
                            new LatLng(Gap_LatLng[0].latitude, Gap_LatLng[0].longitude),
                            new LatLng(Gap_LatLng[1].latitude, Gap_LatLng[1].longitude),
                            new LatLng(Gap_LatLng[2].latitude, Gap_LatLng[2].longitude),
                            new LatLng(Gap_LatLng[3].latitude, Gap_LatLng[3].longitude)));

                    Log.d("Position5", "LatLng[0] : " + Gap_LatLng[0].latitude + " / " + Gap_LatLng[0].longitude);
                    Log.d("Position5", "LatLng[1] : " + Gap_LatLng[1].latitude + " / " + Gap_LatLng[1].longitude);
                    Log.d("Position5", "LatLng[2] : " + Gap_LatLng[2].latitude + " / " + Gap_LatLng[2].longitude);
                    Log.d("Position5", "LatLng[3] : " + Gap_LatLng[3].latitude + " / " + Gap_LatLng[3].longitude);

                    int colorLightBlue = getResources().getColor(R.color.colorLightBlue);

                    polygon.setColor(colorLightBlue);
                    polygon.setMap(naverMap);

                    // 내부 길 생성
                    MakePath();
                }
            }
        });
    }

    private void MakePath() {
        double heading = MyUtil.computeHeading(Auto_Marker.get(0).getPosition(),Auto_Marker.get(1).getPosition());

        Auto_Polyline.add(new LatLng(Auto_Marker.get(0).getPosition().latitude,Auto_Marker.get(0).getPosition().longitude));
        Auto_Polyline.add(new LatLng(Auto_Marker.get(1).getPosition().latitude,Auto_Marker.get(1).getPosition().longitude));

        for(int sum = Gap_Distance; sum + Gap_Distance <= Auto_Distance + Gap_Distance; sum = sum + Gap_Distance)
        {
            LatLng latLng1 = MyUtil.computeOffset(Auto_Marker.get(Auto_Marker_Count-1).getPosition(), Gap_Distance,heading+90);
            LatLng latLng2 = MyUtil.computeOffset(Auto_Marker.get(Auto_Marker_Count-2).getPosition(), Gap_Distance,heading+90);

            Auto_Marker.add(new Marker(latLng1));
            Auto_Marker.add(new Marker(latLng2));
            Auto_Marker_Count += 2;

//            Auto_Marker.get(Auto_Marker_Count-2).getPosition();

            Auto_Polyline.add(new LatLng(Auto_Marker.get(Auto_Marker_Count-2).getPosition().latitude,Auto_Marker.get(Auto_Marker_Count-2).getPosition().longitude));
            Auto_Polyline.add(new LatLng(Auto_Marker.get(Auto_Marker_Count-1).getPosition().latitude,Auto_Marker.get(Auto_Marker_Count-1).getPosition().longitude));
        }

        polylinePath.setColor(Color.WHITE);
        polylinePath.setCoords(Auto_Polyline);
        polylinePath.setMap(naverMap);

        // WayPoint
        MakeWayPoint();
    }

    private void SetTakeOffAltitudeUp() {
        int Altitude = getTakeOffAltitude();
        setTakeOffAltitude(++Altitude);
    }

    private void SetTakeOffAltitudeDown() {
        int Altitude = getTakeOffAltitude();
        setTakeOffAltitude(--Altitude);
    }

    private void deleteStatusBar() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        deleteStatusBar();
        return super.onTouchEvent(event);
    }

    @Override
    protected void onStart() {
        super.onStart();
        this.controlTower.connect(this);
        updateVehicleModesForType(this.droneType);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (this.drone.isConnected()) {
            this.drone.disconnect();
            //updateConnectedButton(false);
        }

        this.controlTower.unregisterDrone(this.drone);
        this.controlTower.disconnect();
    }

    protected void updateVehicleModesForType(int droneType) {
        List<VehicleMode> vehicleModes = VehicleMode.getVehicleModePerDroneType(droneType);
        ArrayAdapter<VehicleMode> vehicleModeArrayAdapter = new ArrayAdapter<VehicleMode>(this, android.R.layout.simple_spinner_item, vehicleModes);
        vehicleModeArrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        this.modeSelector.setAdapter(vehicleModeArrayAdapter);
    }

    @Override
    public void onDroneEvent(String event, Bundle extras) {
        switch (event) {
            case AttributeEvent.STATE_CONNECTED:
                alertUser("드론이 연결되었습니다.");
                break;

            case AttributeEvent.STATE_DISCONNECTED:
                alertUser("드론이 연결 해제되었습니다.");
                break;

            case AttributeEvent.TYPE_UPDATED:
                Type newDroneType = this.drone.getAttribute(AttributeType.TYPE);
                if (newDroneType.getDroneType() != this.droneType) {
                    this.droneType = newDroneType.getDroneType();
                    updateVehicleModesForType(this.droneType);
                }
                break;

            case AttributeEvent.STATE_VEHICLE_MODE:
                updateVehicleMode();
                break;

            case AttributeEvent.GPS_POSITION:
                SetDronePosition();
                break;

            case AttributeEvent.SPEED_UPDATED:
                SpeedUpdate();
                break;

            case AttributeEvent.ALTITUDE_UPDATED:
                AltitudeUpdate();
                break;

            case AttributeEvent.BATTERY_UPDATED:
                BatteryUpdate();
                break;

            case AttributeEvent.STATE_UPDATED:
            case AttributeEvent.STATE_ARMING:
                ArmBtnUpdate();
                break;

            case AttributeEvent.ATTITUDE_UPDATED:
                UpdateYaw();
                break;

            case AttributeEvent.GPS_COUNT:
                ShowSatelliteCount();
                break;

            case AttributeEvent.MISSION_SENT:
                Mission_Sent();
                break;

            case AttributeEvent.MISSION_ITEM_REACHED:
                alertUser(Reached_Count + "번 waypoint 도착");
                Reached_Count++;
                break;

            default:
                MakeRecyclerView();
                // Log.i("DRONE_EVENT", event); //Uncomment to see events from the drone
                break;
        }
    }

    private void Mission_Sent() {
        alertUser("미션 업로드 완료");
        Button BtnSendMission = (Button) findViewById(R.id.BtnSendMission);
        BtnSendMission.setText("임무 시작");
    }

    private void UpdateYaw() {
        // Attitude 받아오기
        Attitude attitude = this.drone.getAttribute(AttributeType.ATTITUDE);
        double yaw = attitude.getYaw();

        // yaw 값을 양수로
        if ((int) yaw < 0) {
            yaw += 360;
        }

        // [UI] yaw 보여주기
        TextView textView_yaw = (TextView) findViewById(R.id.yaw);
        textView_yaw.setText("YAW " + (int) yaw + "deg");
    }

    private void ArmBtnUpdate() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        Button ArmBtn = (Button) findViewById(R.id.BtnArm);

        if (vehicleState.isFlying()) {
            // Land
            ArmBtn.setText("LAND");
        } else if (vehicleState.isArmed()) {
            // Take off
            ArmBtn.setText("TAKE OFF");
        } else if (vehicleState.isConnected()) {
            // Connected but not Armed
            ArmBtn.setText("ARM");
        }
    }

    public void onArmButtonTap(View view) {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);

        if (vehicleState.isFlying()) {
            // Land
            VehicleApi.getApi(this.drone).setVehicleMode(VehicleMode.COPTER_LAND, new SimpleCommandListener() {
                @Override
                public void onError(int executionError) {
                    alertUser("착륙할 수 없습니다 : " + executionError);
                }

                @Override
                public void onTimeout() {
                    alertUser("착륙할 수 없습니다.");
                }
            });
        } else if (vehicleState.isArmed()) {
            // Take off
            ControlApi.getApi(this.drone).takeoff(takeOffAltitude, new AbstractCommandListener() {
                @Override
                public void onSuccess() {
                    alertUser("이륙에 성공하였습니다.");
                }

                @Override
                public void onError(int executionError) {
                    alertUser("이륙 할 수 없습니다 : " + executionError);
                }

                @Override
                public void onTimeout() {
                    alertUser("이륙 할 수 없습니다.");
                }
            });
        } else if (!vehicleState.isConnected()) {
            // Connect
            alertUser("드론 연결을 하십시오.");
        } else {
            // Connected but not Armed
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Arming...");
            builder.setMessage("시동을 걸면 프로펠러가 고속으로 회전합니다.");
            builder.setPositiveButton("예", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    Arming();
                }
            });
            builder.setNegativeButton("아니오", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
        }
    }

    public void Arming() {
        VehicleApi.getApi(this.drone).arm(true, false, new SimpleCommandListener() {
            @Override
            public void onError(int executionError) {
                alertUser("아밍 할 수 없습니다 " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("아밍 할 수 없습니다.");
            }
        });
    }

    private void BatteryUpdate() {
        TextView textView_Vol = (TextView) findViewById(R.id.Voltage);
        Battery battery = this.drone.getAttribute(AttributeType.BATTERY);
        double batteryVoltage = Math.round(battery.getBatteryVoltage() * 10) / 10.0;
        textView_Vol.setText("전압 " + batteryVoltage + "V");
        Log.d("Position8", "Battery : " + batteryVoltage);
    }

    public void SetDronePosition() {
        // 드론 위치 받아오기
        Gps droneGps = this.drone.getAttribute(AttributeType.GPS);
        int Satellite = droneGps.getSatellitesCount();
        LatLong dronePosition = droneGps.getPosition();

        Log.d("Position1", "droneGps : " + droneGps);
        Log.d("Position1", "dronePosition : " + dronePosition);

        // 이동했던 위치 맵에서 지워주기
        if (Marker_Count - 1 >= 0) {
            markers.get(Marker_Count - 1).setMap(null);
        }

        // 마커 리스트에 추가
        markers.add(new Marker(new LatLng(dronePosition.getLatitude(), dronePosition.getLongitude())));

        // yaw 에 따라 네비게이션 마커 회전
        Attitude attitude = this.drone.getAttribute(AttributeType.ATTITUDE);
        double yaw = attitude.getYaw();
        Log.d("Position4", "yaw : " + yaw);
        if ((int) yaw < 0) {
            yaw += 360;
        }
        markers.get(Marker_Count).setAngle((float) yaw);

        // 마커 크기 지정
        markers.get(Marker_Count).setHeight(400);
        markers.get(Marker_Count).setWidth(80);

        // 마커 아이콘 지정
        markers.get(Marker_Count).setIcon(OverlayImage.fromResource(R.drawable.marker_icon));

        // 마커 위치를 중심점으로 지정
        markers.get(Marker_Count).setAnchor(new PointF(0.5F, 0.9F));

        // 마커 띄우기
        markers.get(Marker_Count).setMap(naverMap);

        // 카메라 위치 설정
        Button BtnMapMoveLock = (Button) findViewById(R.id.BtnMapMoveLock);
        String text = (String) BtnMapMoveLock.getText();

        if (text.equals("맵 잠금")) {
            CameraUpdate cameraUpdate = CameraUpdate.scrollTo(new LatLng(dronePosition.getLatitude(), dronePosition.getLongitude()));
            naverMap.moveCamera(cameraUpdate);
        }

        // 지나간 길 Polyline
        Collections.addAll(coords, markers.get(Marker_Count).getPosition());
        polyline.setCoords(coords);

        // 선 예쁘게 설정
        polyline.setWidth(15);
        polyline.setCapType(PolylineOverlay.LineCap.Round);
        polyline.setJoinType(PolylineOverlay.LineJoin.Round);
        polyline.setColor(Color.GREEN);

        polyline.setMap(naverMap);

        Log.d("Position3", "coords.size() : " + coords.size());
        Log.d("Position3", "markers.size() : " + markers.size());

        // 가이드 모드일 때 지정된 좌표와 드론 사이의 거리 측정
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        VehicleMode vehicleMode = vehicleState.getVehicleMode();
        if (vehicleMode == VehicleMode.COPTER_GUIDED) {
            LatLng droneLatLng = new LatLng(markers.get(Marker_Count).getPosition().latitude, markers.get(Marker_Count).getPosition().longitude);
            LatLng goalLatLng = new LatLng(marker_goal.getPosition().latitude, marker_goal.getPosition().longitude);

            double distance = droneLatLng.distanceTo(goalLatLng);

            Log.d("Position9", "distance : " + distance);

            if (distance < 1.0) {
                alertUser("목적지에 도착하였습니다.");
            }
        }

        // [UI] 잡히는 GPS 개수
        ShowSatelliteCount();

        Marker_Count++;
    }

    private void AltitudeUpdate() {
        Altitude currentAltitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        mRecentAltitude = currentAltitude.getRelativeAltitude();
        int newIntAltitude = (int) Math.round(mRecentAltitude);

        TextView textView = (TextView) findViewById(R.id.Altitude);
        Altitude altitude = this.drone.getAttribute(AttributeType.ALTITUDE);
        int intAltitude = (int) Math.round(altitude.getAltitude());
        textView.setText("고도 " + newIntAltitude + "m");
        Log.d("Position7", "Altitude : " + altitude);
    }

    private void SpeedUpdate() {
        TextView textView = (TextView) findViewById(R.id.Speed);
        Speed speed = this.drone.getAttribute(AttributeType.SPEED);
        int doubleSpeed = (int) Math.round(speed.getGroundSpeed());
        // double doubleSpeed = Math.round(speed.getGroundSpeed()*10)/10.0; 소수점 첫째자리까지
        textView.setText("속도 " + doubleSpeed + "m/s");
        Log.d("Position6", "Speed : " + this.drone.getAttribute(AttributeType.SPEED));
    }

    public void onFlightModeSelected(View view) {
        final VehicleMode vehicleMode = (VehicleMode) this.modeSelector.getSelectedItem();

        VehicleApi.getApi(this.drone).setVehicleMode(vehicleMode, new AbstractCommandListener() {
            @Override
            public void onSuccess() {
                alertUser("비행 모드 " + vehicleMode.toString() + "로 변경.");
            }

            @Override
            public void onError(int executionError) {
                alertUser("비행 모드 변경 실패 : " + executionError);
            }

            @Override
            public void onTimeout() {
                alertUser("비행 모드 변경 시간 초과.");
            }
        });
    }

    protected void updateVehicleMode() {
        State vehicleState = this.drone.getAttribute(AttributeType.STATE);
        VehicleMode vehicleMode = vehicleState.getVehicleMode();
        ArrayAdapter arrayAdapter = (ArrayAdapter) this.modeSelector.getAdapter();
        this.modeSelector.setSelection(arrayAdapter.getPosition(vehicleMode));
    }

    @Override
    public void onDroneServiceInterrupted(String errorMsg) {

    }

    @Override
    public void onLinkStateUpdated(@NonNull LinkConnectionStatus connectionStatus) {
        switch (connectionStatus.getStatusCode()) {
            case LinkConnectionStatus.FAILED:
                Bundle extras = connectionStatus.getExtras();
                String msg = null;
                if (extras != null) {
                    msg = extras.getString(LinkConnectionStatus.EXTRA_ERROR_MSG);
                }
                alertUser("연결 실패 :" + msg);
                break;
        }
    }

    @Override
    public void onTowerConnected() {
        alertUser("드론-핸드폰 연결 성공.");
        this.controlTower.registerDrone(this.drone, this.handler);
        this.drone.registerDroneListener(this);
    }

    @Override
    public void onTowerDisconnected() {
        alertUser("드론-핸드폰 연결 해제.");
    }

    private void MakeRecyclerView() {
        LocalTime localTime = LocalTime.now();

        // recycler view 시간 지나면 제거
        if (recycler_list.size() > 0) {
            Log.d("Position2", "---------------------------------------------------");
            Log.d("Position2", "[Minute] recycler time : " + recycler_time.get(Recycler_Count).getMinute());
            Log.d("Position2", "[Minute] Local time : " + localTime.getMinute());
            if (recycler_time.get(Recycler_Count).getMinute() == localTime.getMinute()) {
                Log.d("Position2", "recycler time : " + recycler_time.get(Recycler_Count).getSecond());
                Log.d("Position2", "Local time : " + localTime.getSecond());
                Log.d("Position2", "[★] recycler size() : " + recycler_list.size());
                Log.d("Position2", "[★] Recycler_Count : " + Recycler_Count);
                if (localTime.getSecond() >= recycler_time.get(Recycler_Count).getSecond() + 3) {
                    RemoveRecyclerView();
                }
            } else {
                // 3초가 지났을 때 1분이 지나감
                Log.d("Position2", "recycler time : " + recycler_time.get(Recycler_Count).getSecond());
                Log.d("Position2", "Local time : " + localTime.getSecond());
                Log.d("Position2", "[★] recycler size() : " + recycler_list.size());
                Log.d("Position2", "[★] Recycler_Count : " + Recycler_Count);
                if (localTime.getSecond() + 60 >= recycler_time.get(Recycler_Count).getSecond() + 3) {
                    RemoveRecyclerView();
                }
            }
            Log.d("Position2", "---------------------------------------------------");
        }
    }

    private void RemoveRecyclerView() {
        recycler_list.remove(Recycler_Count);
        recycler_time.remove(Recycler_Count);
        if (recycler_list.size() > Recycler_Count) {
            LocalTime localTime = LocalTime.now();
            recycler_time.set(Recycler_Count, localTime);
        }

        RecyclerView recyclerView = findViewById(R.id.recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        SimpleTextAdapter adapter = new SimpleTextAdapter(recycler_list);
        recyclerView.setAdapter(adapter);

        // 리사이클러뷰에 애니메이션 추가.
        Animation animation = AnimationUtils.loadAnimation(this, R.anim.item_animation_down_to_up);
        recyclerView.startAnimation(animation);
    }

    private void alertUser(String message) {
//        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
//        Log.d(TAG, message);

        // 5개 이상 삭제
        if (recycler_list.size() > 3) {
            recycler_list.remove(Recycler_Count);
        }

        LocalTime localTime = LocalTime.now();
        recycler_list.add(String.format("  ★  " + message));
        recycler_time.add(localTime);

        // 리사이클러뷰에 LinearLayoutManager 객체 지정.
        RecyclerView recyclerView = findViewById(R.id.recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 리사이클러뷰에 SimpleAdapter 객체 지정.
        SimpleTextAdapter adapter = new SimpleTextAdapter(recycler_list);
        recyclerView.setAdapter(adapter);
    }
}