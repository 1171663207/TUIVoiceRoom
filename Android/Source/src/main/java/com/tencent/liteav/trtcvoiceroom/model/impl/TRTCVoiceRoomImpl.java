package com.tencent.liteav.trtcvoiceroom.model.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.tencent.liteav.audio.TXAudioEffectManager;
import com.tencent.liteav.debug.GenerateTestUserSig;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoom;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoomCallback;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoomDef;
import com.tencent.liteav.trtcvoiceroom.model.TRTCVoiceRoomDelegate;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TRTCLogger;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TXCallback;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TXRoomInfo;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TXSeatInfo;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TXUserInfo;
import com.tencent.liteav.trtcvoiceroom.model.impl.base.TXUserListCallback;
import com.tencent.liteav.trtcvoiceroom.model.impl.room.ITXRoomServiceDelegate;
import com.tencent.liteav.trtcvoiceroom.model.impl.room.impl.TXRoomService;
import com.tencent.liteav.trtcvoiceroom.model.impl.trtc.VoiceRoomTRTCService;
import com.tencent.liteav.trtcvoiceroom.model.impl.trtc.VoiceRoomTRTCServiceDelegate;
import com.tencent.trtc.TRTCCloudDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TRTCVoiceRoomImpl extends TRTCVoiceRoom implements ITXRoomServiceDelegate, VoiceRoomTRTCServiceDelegate {
    private static final String TAG = TRTCVoiceRoomImpl.class.getName();

    public enum MOVE_SEAT {
        ENTER, //移麦进入新麦位
        LEAVE  //移麦离开原麦位
    }

    private static TRTCVoiceRoomImpl     sInstance;
    private static long  CALL_MOVE_SEAT_LIMIT_TIME = 1000; //moveSeat接口限频，默认1s
    private final  Context               mContext;
    private        TRTCVoiceRoomDelegate mDelegate;
    // 所有调用都切到主线程使用，保证内部多线程安全问题
    private        Handler               mMainHandler;
    // 外部可指定的回调线程
    private        Handler               mDelegateHandler;
    private        int                   mSdkAppId;
    private        String                mUserId;
    private        String                mUserSig;
    private        long                  mLastCallMoveSeatTime;

    // 主播列表
    private Set<String>                          mAnchorList;
    // 已抛出的观众列表
    private Set<String>                          mAudienceList;
    private List<TRTCVoiceRoomDef.SeatInfo>      mSeatInfoList;
    private TRTCVoiceRoomCallback.ActionCallback mEnterSeatCallback;
    private TRTCVoiceRoomCallback.ActionCallback mLeaveSeatCallback;
    private TRTCVoiceRoomCallback.ActionCallback mPickSeatCallback;
    private TRTCVoiceRoomCallback.ActionCallback mKickSeatCallback;
    private TRTCVoiceRoomCallback.ActionCallback mMoveSeatCallback;
    private int                                  mTakeSeatIndex;
    private Set<MOVE_SEAT>                       mMoveSet;
    private boolean                              mIsMoveSeat;

    public static synchronized TRTCVoiceRoom sharedInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TRTCVoiceRoomImpl(context.getApplicationContext());
        }
        return sInstance;
    }

    public static synchronized void destroySharedInstance() {
        if (sInstance != null) {
            sInstance.destroy();
            sInstance = null;
        }
    }

    private void destroy() {
        TXRoomService.getInstance().destroy();
    }

    private TRTCVoiceRoomImpl(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mDelegateHandler = new Handler(Looper.getMainLooper());
        mSeatInfoList = new ArrayList<>();
        mAnchorList = new HashSet<>();
        mAudienceList = new HashSet<>();
        mMoveSet = new HashSet<>();
        mTakeSeatIndex = -1;
        VoiceRoomTRTCService.getInstance().setDelegate(this);
        VoiceRoomTRTCService.getInstance().init(context);
        TXRoomService.getInstance().init(context);
        TXRoomService.getInstance().setDelegate(this);
    }

    private void clearList() {
        mSeatInfoList.clear();
        mAnchorList.clear();
        mAudienceList.clear();
        mMoveSet.clear();
    }

    private void runOnMainThread(Runnable runnable) {
        Handler handler = mMainHandler;
        if (handler != null) {
            if (handler.getLooper() == Looper.myLooper()) {
                runnable.run();
            } else {
                handler.post(runnable);
            }
        } else {
            runnable.run();
        }
    }

    private void runOnDelegateThread(Runnable runnable) {
        Handler handler = mDelegateHandler;
        if (handler != null) {
            if (handler.getLooper() == Looper.myLooper()) {
                runnable.run();
            } else {
                handler.post(runnable);
            }
        } else {
            runnable.run();
        }
    }

    @Override
    public void setDelegate(TRTCVoiceRoomDelegate delegate) {
        mDelegate = delegate;
    }

    @Override
    public void setDelegateHandler(Handler handler) {
        mDelegateHandler = handler;
    }

    @Override
    public void login(final int sdkAppId, final String userId, final String userSig, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start login, sdkAppId:" + sdkAppId + " userId:" + userId + " sign is empty:" + TextUtils.isEmpty(userSig) + " app version: " + TRTCVoiceRoomDef.APP_VERSION);
                if (sdkAppId == 0 || TextUtils.isEmpty(userId) || TextUtils.isEmpty(userSig)) {
                    TRTCLogger.e(TAG, "start login fail. params invalid.");
                    if (callback != null) {
                        callback.onCallback(-1, "登录失败，参数有误");
                    }
                    return;
                }
                mSdkAppId = sdkAppId;
                mUserId = userId;
                mUserSig = userSig;
                TRTCLogger.i(TAG, "start login room service");
                TXRoomService.getInstance().login(sdkAppId, userId, userSig, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void logout(final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start logout");
                mSdkAppId = 0;
                mUserId = "";
                mUserSig = "";
                TRTCLogger.i(TAG, "start logout room service");
                TXRoomService.getInstance().logout(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "logout room service finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void setSelfProfile(final String userName, final String avatarURL, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "set profile, user name:" + userName + " avatar url:" + avatarURL);
                TXRoomService.getInstance().setSelfProfile(userName, avatarURL, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "set profile finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void createRoom(final int roomId, final TRTCVoiceRoomDef.RoomParam roomParam, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "create room, room id:" + roomId + " info:" + roomParam + " app version: " + TRTCVoiceRoomDef.APP_VERSION);
                if (roomId == 0) {
                    TRTCLogger.e(TAG, "create room fail. params invalid");
                    return;
                }

                final String strRoomId = String.valueOf(roomId);

                clearList();

                final String           roomName       = (roomParam == null ? "" : roomParam.roomName);
                final String           roomCover      = (roomParam == null ? "" : roomParam.coverUrl);
                final boolean          isNeedRequest  = (roomParam != null && roomParam.needRequest);
                final int              seatCount      = (roomParam == null ? 8 : roomParam.seatCount);
                final List<TXSeatInfo> txSeatInfoList = new ArrayList<>();
                if (roomParam != null && roomParam.seatInfoList != null) {
                    for (TRTCVoiceRoomDef.SeatInfo seatInfo : roomParam.seatInfoList) {
                        TXSeatInfo item = new TXSeatInfo();
                        item.status = seatInfo.status;
                        item.mute = seatInfo.mute;
                        item.user = seatInfo.userId;
                        txSeatInfoList.add(item);
                        mSeatInfoList.add(seatInfo);
                    }
                } else {
                    for (int i = 0; i < seatCount; i++) {
                        txSeatInfoList.add(new TXSeatInfo());
                        mSeatInfoList.add(new TRTCVoiceRoomDef.SeatInfo());
                    }
                }
                // 创建房间
                TXRoomService.getInstance().createRoom(strRoomId, roomName, roomCover, isNeedRequest, txSeatInfoList, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "create room in service, code:" + code + " msg:" + msg);
                        if (code == 0) {
                            enterTRTCRoomInner(strRoomId, mUserId, mUserSig, TRTCCloudDef.TRTCRoleAnchor, callback);
                            return;
                        } else {
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDelegate != null) {
                                        mDelegate.onError(code, msg);
                                    }
                                }
                            });
                        }
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void destroyRoom(final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start destroy room.");
                // TRTC 房间退房结果不关心
                TRTCLogger.i(TAG, "start exit trtc room.");
                VoiceRoomTRTCService.getInstance().exitRoom(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "exit trtc room finish, code:" + code + " msg:" + msg);
                        if (code != 0) {
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDelegate != null) {
                                        mDelegate.onError(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });

                TRTCLogger.i(TAG, "start destroy room service.");
                TXRoomService.getInstance().destroyRoom(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "destroy room finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });

                // 恢复设定
                clearList();
            }
        });
    }


    @Override
    public void enterRoom(final int roomId, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                // 恢复设定
                clearList();
                String strRoomId = String.valueOf(roomId);
                TRTCLogger.i(TAG, "start enter room, room id:" + roomId + " app version: " + TRTCVoiceRoomDef.APP_VERSION);
                enterTRTCRoomInner(strRoomId, mUserId, mUserSig, TRTCCloudDef.TRTCRoleAudience, new TRTCVoiceRoomCallback.ActionCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "trtc enter room finish, room id:" + roomId + " code:" + code + " msg:" + msg);
                        //                        runOnMainThread(new Runnable() {
                        //                            @Override
                        //                            public void run() {
                        //                                if (code == 0) {
                        //                                }
                        //                            }
                        //                        });
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
                TXRoomService.getInstance().enterRoom(strRoomId, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "enter room service finish, room id:" + roomId + " code:" + code + " msg:" + msg);
                        runOnMainThread(new Runnable() {
                            @Override
                            public void run() {
                                if (code != 0) {
                                    runOnDelegateThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mDelegate != null) {
                                                mDelegate.onError(code, msg);
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void exitRoom(final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start exit room.");
                // 退房的时候需要判断主播是否在座位，如果是麦上主播，需要先清空座位列表
                if (isOnSeat(mUserId)) {
                    leaveSeat(new TRTCVoiceRoomCallback.ActionCallback() {
                        @Override
                        public void onCallback(int code, String msg) {
                            exitRoomInternal(callback);
                        }
                    });
                } else {
                    exitRoomInternal(callback);
                }
            }
        });
    }

    private void exitRoomInternal(final TRTCVoiceRoomCallback.ActionCallback callback) {
        VoiceRoomTRTCService.getInstance().exitRoom(new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                if (code != 0) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mDelegate != null) {
                                mDelegate.onError(code, msg);
                            }
                        }
                    });
                }
            }
        });
        TRTCLogger.i(TAG, "start exit room service.");
        TXRoomService.getInstance().exitRoom(new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                TRTCLogger.i(TAG, "exit room finish, code:" + code + " msg:" + msg);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
        clearList();
    }

    private boolean isOnSeat(String userId) {
        // 判断某个userid 是不是在座位上
        if (mSeatInfoList == null) {
            return false;
        }
        for (TRTCVoiceRoomDef.SeatInfo seatInfo : mSeatInfoList) {
            if (userId != null && userId.equals(seatInfo.userId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void getUserInfoList(final List<String> userIdList, final TRTCVoiceRoomCallback.UserListCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userIdList == null) {
                    getAudienceList(callback);
                    return;
                }
                TXRoomService.getInstance().getUserInfo(userIdList, new TXUserListCallback() {
                    @Override
                    public void onCallback(final int code, final String msg, final List<TXUserInfo> list) {
                        TRTCLogger.i(TAG, "get audience list finish, code:" + code + " msg:" + msg + " list:" + (list != null ? list.size() : 0));
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    List<TRTCVoiceRoomDef.UserInfo> userList = new ArrayList<>();
                                    if (list != null) {
                                        for (TXUserInfo info : list) {
                                            TRTCVoiceRoomDef.UserInfo trtcUserInfo = new TRTCVoiceRoomDef.UserInfo();
                                            trtcUserInfo.userId = info.userId;
                                            trtcUserInfo.userAvatar = info.avatarURL;
                                            trtcUserInfo.userName = info.userName;
                                            userList.add(trtcUserInfo);
                                            TRTCLogger.i(TAG, "info:" + info);
                                        }
                                    }
                                    callback.onCallback(code, msg, userList);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void getAudienceList(final TRTCVoiceRoomCallback.UserListCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TXRoomService.getInstance().getAudienceList(new TXUserListCallback() {
                    @Override
                    public void onCallback(final int code, final String msg, final List<TXUserInfo> list) {
                        TRTCLogger.i(TAG, "get audience list finish, code:" + code + " msg:" + msg + " list:" + (list != null ? list.size() : 0));
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    List<TRTCVoiceRoomDef.UserInfo> userList = new ArrayList<>();
                                    if (list != null) {
                                        for (TXUserInfo info : list) {
                                            TRTCVoiceRoomDef.UserInfo trtcUserInfo = new TRTCVoiceRoomDef.UserInfo();
                                            trtcUserInfo.userId = info.userId;
                                            trtcUserInfo.userAvatar = info.avatarURL;
                                            trtcUserInfo.userName = info.userName;
                                            userList.add(trtcUserInfo);
                                            TRTCLogger.i(TAG, "info:" + info);
                                        }
                                    }
                                    callback.onCallback(code, msg, userList);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void enterSeat(final int seatIndex, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "enterSeat " + seatIndex);
                if (isOnSeat(mUserId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            TRTCLogger.i(TAG, "you are already in the seat");
                            if (callback != null) {
                                callback.onCallback(-1, "you are already in the seat");
                            }
                        }
                    });
                    return;
                }
                mEnterSeatCallback = callback;
                TXRoomService.getInstance().takeSeat(seatIndex, new TXCallback() {
                    @Override
                    public void onCallback(int code, String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mEnterSeatCallback = null;
                            mTakeSeatIndex = -1;
                            if (callback != null) {
                                callback.onCallback(code, msg);
                            }
                        } else {
                            TRTCLogger.i(TAG, "take seat callback success, and wait attrs changed.");
                        }
                    }
                });
            }
        });
    }

    @Override
    public void leaveSeat(final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "leaveSeat " + mTakeSeatIndex);
                if (mTakeSeatIndex == -1) {
                    //已经不再座位上了
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                TRTCLogger.i(TAG, "you are not in the seat");
                                callback.onCallback(-1, "you are not in the seat");
                            }
                        }
                    });
                    return;
                }
                mLeaveSeatCallback = callback;
                TXRoomService.getInstance().leaveSeat(mTakeSeatIndex, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mLeaveSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void pickSeat(final int seatIndex, final String userId, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                //判断该用户是否已经在麦上
                TRTCLogger.i(TAG, "pickSeat " + seatIndex);
                if (isOnSeat(userId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                TRTCLogger.i(TAG, "this user are in the seat");
                                callback.onCallback(-1, "this user are in the seat");
                            }
                        }
                    });
                    return;
                }
                mPickSeatCallback = callback;
                TXRoomService.getInstance().pickSeat(seatIndex, userId, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mPickSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void kickSeat(final int index, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "kickSeat " + index);
                mKickSeatCallback = callback;
                TXRoomService.getInstance().kickSeat(index, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mKickSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void muteSeat(final int seatIndex, final boolean isMute, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "muteSeat " + seatIndex + " " + isMute);
                TXRoomService.getInstance().muteSeat(seatIndex, isMute, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void closeSeat(final int seatIndex, final boolean isClose, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "closeSeat " + seatIndex + " " + isClose);
                TXRoomService.getInstance().closeSeat(seatIndex, isClose, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public int moveSeat(final int seatIndex, final TRTCVoiceRoomCallback.ActionCallback callback) {
        if (System.currentTimeMillis() - mLastCallMoveSeatTime < CALL_MOVE_SEAT_LIMIT_TIME) {
            TRTCLogger.i(TAG, "moveSeat call limit: " + CALL_MOVE_SEAT_LIMIT_TIME);
            mLastCallMoveSeatTime = System.currentTimeMillis();
            return TRTCVoiceRoomDef.ERR_CALL_METHOD_LIMIT;
        }
        mLastCallMoveSeatTime = System.currentTimeMillis();
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "moveSeat : " + seatIndex);
                if (!isOnSeat(mUserId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onCallback(-1, "you are not in the seat");
                            }
                        }
                    });
                    return;
                }
                mMoveSeatCallback = callback;
                mMoveSet.clear();
                mMoveSet.add(MOVE_SEAT.ENTER);
                mMoveSet.add(MOVE_SEAT.LEAVE);
                TXRoomService.getInstance().moveSeat(seatIndex, new TXCallback() {
                    @Override
                    public void onCallback(int code, String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mMoveSeatCallback = null;
                            mMoveSet.clear();
                            if (callback != null) {
                                callback.onCallback(code, msg);
                            }
                        } else {
                            TRTCLogger.i(TAG, "move seat callback success, and wait attrs changed.");
                        }
                    }
                });
            }
        });
        return 0;
    }

    @Override
    public void startMicrophone() {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().startMicrophone();
            }
        });
    }

    @Override
    public void stopMicrophone() {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().stopMicrophone();
            }
        });
    }

    @Override
    public void setAudioQuality(final int quality) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().setAudioQuality(quality);
            }
        });
    }

    @Override
    public void setVoiceEarMonitorEnable(final boolean enable) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().enableAudioEarMonitoring(enable);
            }
        });
    }

    /**
     * 静音本地
     * <p>
     * 直接调用 TRTC 设置：TXTRTCLiveRoom.muteLocalAudio
     *
     * @param mute
     */
    @Override
    public void muteLocalAudio(final boolean mute) {
        TRTCLogger.i(TAG, "mute local audio, mute:" + mute);
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().muteLocalAudio(mute);
            }
        });
    }

    @Override
    public void setSpeaker(final boolean useSpeaker) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().setSpeaker(useSpeaker);
            }
        });
    }

    @Override
    public void setAudioCaptureVolume(final int volume) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().setAudioCaptureVolume(volume);
            }
        });
    }

    @Override
    public void setAudioPlayoutVolume(final int volume) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                VoiceRoomTRTCService.getInstance().setAudioPlayoutVolume(volume);
            }
        });
    }

    /**
     * 静音音频
     *
     * @param userId
     * @param mute
     */
    @Override
    public void muteRemoteAudio(final String userId, final boolean mute) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "mute trtc audio, user id:" + userId);
                VoiceRoomTRTCService.getInstance().muteRemoteAudio(userId, mute);
            }
        });
    }

    /**
     * 静音所有音频
     *
     * @param mute
     */
    @Override
    public void muteAllRemoteAudio(final boolean mute) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "mute all trtc remote audio success, mute:" + mute);
                VoiceRoomTRTCService.getInstance().muteAllRemoteAudio(mute);
            }
        });
    }


    @Override
    public TXAudioEffectManager getAudioEffectManager() {
        return VoiceRoomTRTCService.getInstance().getAudioEffectManager();
    }

    @Override
    public void sendRoomTextMsg(final String message, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "sendRoomTextMsg");
                TXRoomService.getInstance().sendRoomTextMsg(message, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void sendRoomCustomMsg(final String cmd, final String message, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "sendRoomCustomMsg");
                TXRoomService.getInstance().sendRoomCustomMsg(cmd, message, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public String sendInvitation(final String cmd, final String userId, final String content, final TRTCVoiceRoomCallback.ActionCallback callback) {
        TRTCLogger.i(TAG, "sendInvitation to " + userId + " cmd:" + cmd + " content:" + content);
        return TXRoomService.getInstance().sendInvitation(cmd, userId, content, new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void acceptInvitation(final String id, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "acceptInvitation " + id);
                TXRoomService.getInstance().acceptInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void rejectInvitation(final String id, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "rejectInvitation " + id);
                TXRoomService.getInstance().rejectInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void cancelInvitation(final String id, final TRTCVoiceRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "cancelInvitation " + id);
                TXRoomService.getInstance().cancelInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void enterTRTCRoomInner(final String roomId, final String userId, final String userSig, final int role, final TRTCVoiceRoomCallback.ActionCallback callback) {
        // 进入 TRTC 房间
        TRTCLogger.i(TAG, "enter trtc room.");
        VoiceRoomTRTCService.getInstance().enterRoom(mSdkAppId, roomId, userId, userSig, role, new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                TRTCLogger.i(TAG, "enter trtc room finish, code:" + code + " msg:" + msg);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRoomDestroy(final String roomId) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                exitRoom(null);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mDelegate != null) {
                            mDelegate.onRoomDestroy(roomId);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRoomRecvRoomTextMsg(final String roomId, final String message, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo throwUser = new TRTCVoiceRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onRecvRoomTextMsg(message, throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomRecvRoomCustomMsg(final String roomId, final String cmd, final String message, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo throwUser = new TRTCVoiceRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onRecvRoomCustomMsg(cmd, message, throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomInfoChange(final TXRoomInfo tXRoomInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                TRTCVoiceRoomDef.RoomInfo roomInfo = new TRTCVoiceRoomDef.RoomInfo();
                roomInfo.roomName = tXRoomInfo.roomName;
                int translateRoomId = 0;
                try {
                    translateRoomId = Integer.parseInt(tXRoomInfo.roomId);
                } catch (NumberFormatException e) {
                    TRTCLogger.e(TAG, e.getMessage());
                }
                roomInfo.roomId = translateRoomId;
                roomInfo.ownerId = tXRoomInfo.ownerId;
                roomInfo.ownerName = tXRoomInfo.ownerName;
                roomInfo.coverUrl = tXRoomInfo.cover;
                roomInfo.memberCount = tXRoomInfo.memberCount;
                roomInfo.needRequest = (tXRoomInfo.needRequest == 1);
                if (mDelegate != null) {
                    mDelegate.onRoomInfoChange(roomInfo);
                }
            }
        });
    }

    @Override
    public void onSeatInfoListChange(final List<TXSeatInfo> tXSeatInfoList) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                List<TRTCVoiceRoomDef.SeatInfo> seatInfoList = new ArrayList<>();
                for (TXSeatInfo seatInfo : tXSeatInfoList) {
                    TRTCVoiceRoomDef.SeatInfo info = new TRTCVoiceRoomDef.SeatInfo();
                    info.userId = seatInfo.user;
                    info.mute = seatInfo.mute;
                    info.status = seatInfo.status;
                    seatInfoList.add(info);
                }
                mSeatInfoList = seatInfoList;
                if (mDelegate != null) {
                    mDelegate.onSeatListChange(seatInfoList);
                }
            }
        });
    }

    @Override
    public void onRoomAudienceEnter(final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo throwUser = new TRTCVoiceRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onAudienceEnter(throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomAudienceLeave(final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo throwUser = new TRTCVoiceRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onAudienceExit(throwUser);
                }
            }
        });
    }

    @Override
    public void onSeatTake(final int index, final TXUserInfo userInfo) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userInfo.userId.equals(mUserId)) {
                    boolean mute = mSeatInfoList.get(index).mute;
                    if (mMoveSet.isEmpty()) {
                        VoiceRoomTRTCService.getInstance().muteLocalAudio(mute);
                        if (!mute) {
                            mDelegate.onUserMicrophoneMute(userInfo.userId, false);
                        }
                        VoiceRoomTRTCService trtcService = VoiceRoomTRTCService.getInstance();
                        trtcService.switchToAnchor(new VoiceRoomTRTCService.OnSwitchListener() {
                            @Override
                            public void onTRTCSwitchRole(int code, String message) {
                                onSwitchToAnchor(index, userInfo);
                            }
                        });
                    } else { //移麦场景
                        if (mute) {
                            VoiceRoomTRTCService.getInstance().muteLocalAudio(true);
                            mDelegate.onUserMicrophoneMute(userInfo.userId, true);
                        }
                        onSwitchToAnchor(index, userInfo);
                    }
                }
            }
        });
    }

    private void onSwitchToAnchor(final int index, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo info = new TRTCVoiceRoomDef.UserInfo();
                    info.userId = userInfo.userId;
                    info.userAvatar = userInfo.avatarURL;
                    info.userName = userInfo.userName;
                    mDelegate.onAnchorEnterSeat(index, info);
                }
                if (mPickSeatCallback != null) {
                    mPickSeatCallback.onCallback(0, "pick seat success");
                    mPickSeatCallback = null;
                }
            }
        });
        if (userInfo.userId.equals(mUserId)) {
            //是自己上线了, 切换角色
            mTakeSeatIndex = index;
            //在回调出去
            runOnDelegateThread(new Runnable() {
                @Override
                public void run() {
                    if (mMoveSeatCallback != null) {
                        mMoveSet.remove(MOVE_SEAT.ENTER);
                        if (mMoveSet.isEmpty()) {
                            mMoveSeatCallback.onCallback(0, "move seat success");
                            mMoveSeatCallback = null;
                        }
                    } else if (mEnterSeatCallback != null) {
                        mEnterSeatCallback.onCallback(0, "enter seat success");
                        mEnterSeatCallback = null;
                    }
                }
            });
        }
    }

    @Override
    public void onSeatClose(final int index, final boolean isClose) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (mTakeSeatIndex == index && isClose) {
                    VoiceRoomTRTCService.getInstance().switchToAudience(new VoiceRoomTRTCService.OnSwitchListener() {
                        @Override
                        public void onTRTCSwitchRole(int code, String message) {
                            mTakeSeatIndex = -1;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDelegate != null) {
                                        mDelegate.onSeatClose(index, true);
                                    }
                                }
                            });
                        }
                    });
                } else {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mDelegate != null) {
                                mDelegate.onSeatClose(index, isClose);
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onSeatLeave(final int index, final TXUserInfo userInfo) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userInfo.userId.equals(mUserId) && mMoveSet.isEmpty()) {
                    VoiceRoomTRTCService trtcService = VoiceRoomTRTCService.getInstance();
                    trtcService.switchToAudience(new VoiceRoomTRTCService.OnSwitchListener() {
                        @Override
                        public void onTRTCSwitchRole(int code, String message) {
                            onSwitchToAudience(index, userInfo);
                        }
                    });
                } else {
                    onSwitchToAudience(index, userInfo);
                }
            }
        });
    }
    private void onSwitchToAudience(final int index, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCVoiceRoomDef.UserInfo info = new TRTCVoiceRoomDef.UserInfo();
                    info.userId = userInfo.userId;
                    info.userAvatar = userInfo.avatarURL;
                    info.userName = userInfo.userName;
                    mDelegate.onAnchorLeaveSeat(index, info);
                }
                if (mKickSeatCallback != null) {
                    mKickSeatCallback.onCallback(0, "kick seat success");
                    mKickSeatCallback = null;
                }
            }
        });
        if (userInfo.userId.equals(mUserId)) {
            runOnDelegateThread(new Runnable() {
                @Override
                public void run() {
                    if (mMoveSeatCallback != null) {
                        mMoveSet.remove(MOVE_SEAT.LEAVE);
                        if (mMoveSet.isEmpty()) {
                            mMoveSeatCallback.onCallback(0, "move seat success");
                            mMoveSeatCallback = null;
                        }
                    } else if (mLeaveSeatCallback != null) {
                        mTakeSeatIndex = -1;
                        mLeaveSeatCallback.onCallback(0, "leave seat success");
                        mLeaveSeatCallback = null;
                    } else {
                        mTakeSeatIndex = -1;
                    }
                }
            });
        }
    }

    @Override
    public void onSeatMute(final int index, final boolean mute) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onSeatMute(index, mute);
                }
            }
        });
    }

    @Override
    public void onReceiveNewInvitation(final String id, final String inviter, final String cmd, final String content) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onReceiveNewInvitation(id, inviter, cmd, content);
                }
            }
        });
    }

    @Override
    public void onInviteeAccepted(final String id, final String invitee) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInviteeAccepted(id, invitee);
                }
            }
        });
    }

    @Override
    public void onInviteeRejected(final String id, final String invitee) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInviteeRejected(id, invitee);
                }
            }
        });
    }

    @Override
    public void onInvitationCancelled(final String id, final String inviter) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInvitationCancelled(id, inviter);
                }
            }
        });
    }

    @Override
    public void onTRTCAnchorEnter(String userId) {
        mAnchorList.add(userId);
    }

    @Override
    public void onTRTCAnchorExit(String userId) {
        mAnchorList.remove(userId);
    }

    @Override
    public void onTRTCAudioAvailable(final String userId, final boolean available) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onUserMicrophoneMute(userId, !available);
                }
            }
        });
    }

    @Override
    public void onError(final int errorCode, final String errorMsg) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onError(errorCode, errorMsg);
                }
            }
        });
    }

    @Override
    public void onNetworkQuality(TRTCCloudDef.TRTCQuality trtcQuality, ArrayList<TRTCCloudDef.TRTCQuality> arrayList) {

    }

    @Override
    public void onUserVoiceVolume(final ArrayList<TRTCCloudDef.TRTCVolumeInfo> userVolumes, final int totalVolume) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null && userVolumes != null) {
                    mDelegate.onUserVolumeUpdate(userVolumes, totalVolume);
                }
            }
        });
    }
}
