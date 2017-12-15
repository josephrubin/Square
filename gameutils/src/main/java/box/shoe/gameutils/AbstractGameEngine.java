package box.shoe.gameutils;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.support.annotation.IntDef;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import box.gift.gameutils.R;

/**
 * Created by Joseph on 10/23/2017.
 *
 * The game engine runs game update code on a new thread (how often it runs is supplied by the UPS parameter in the constructor)
 * And it runs visual update code on another new thread, painted to the supplied GameSurfaceView
 * It paints every new VSYNC (before a new frame wants to be drawn)
 * Therefore game updates may happen less frequently than the game is painted (and the two are not aligned)
 * So the engine attempts to make interpolations in order to draw.
 * This means that rendering will always be at most one frame behind user input (not a big deal!)
 * This is because we interpolate between the previous update and the latest update
 */

public abstract class AbstractGameEngine extends AbstractEventDispatcher implements Cleanable
{
    // Define the possible UPS options, which are factors of 1000 (so we get an even number of MS per update).
    // This is not a hard requirement, and the annotation may be suppressed,
    // at the risk of possible jittery frame display.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({1, 2, 4, 5, 8, 10, 20, 25, 40, 50, 100, 125, 200, 250, 500, 1000})
    public @interface UPS_Options {} //Bug - @interface should be syntax highlighted annotation color (yellow), not keyword color (blue)

    private int targetUPS;
    private final long expectedUpdateTimeMS;
    private final long expectedUpdateTimeNS;

    private final AbstractGameSurfaceView gameScreen;
    private volatile boolean started = false;
    private Context appContext;

    // Control
    private volatile boolean stopped = false;
    private volatile boolean paused = false;
    private volatile boolean frameThreadPaused = false;
    private volatile boolean updateThreadPaused = false;
    private volatile boolean oneFrameThenPause = false;

    // Threads
    private Thread updateThread;
    private Thread frameThread;

    private volatile boolean wantToLaunch = false;
    private volatile boolean viewHasDimension = false;

    // Monitors
    private final Object updateAndFrameAndInputMonitor = new Object();
    private CountDownLatch pauseLatch;

    // Objs
    private Choreographer vsync;
    protected Rand random;
    protected Vibrator rumble;
    private List<GameState> gameStates; // We want to use it like a queue, but we need to access the first two elements, so it cannot be one
    private GameState lastVisualizedGameState;

    // Etc
    public volatile int score;
    private long vsyncOffsetNanos = 0; // Default of 0 if not high enough API to get he real value

    public AbstractGameEngine(Context appContext, @UPS_Options int targetUPS, AbstractGameSurfaceView screen) //target ups should divide evenly into 1000000000, updates are accurately caleld to within about 10ms
    {
        this.appContext = appContext;

        Display display = ((WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        //this.refreshRate = display.getRefreshRate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            vsyncOffsetNanos = display.getAppVsyncOffsetNanos();
        }

        this.targetUPS = targetUPS;
        this.expectedUpdateTimeMS = 1000 / this.targetUPS;
        this.expectedUpdateTimeNS = this.expectedUpdateTimeMS * 1000000;

        gameStates = new LinkedList<>();
        random = new Rand();
        rumble = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);

        updateThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                runUpdates();
            }
        }, appContext.getString(R.string.update_thread_name));

        frameThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                runFrames();
            }
        }, appContext.getString(R.string.frame_thread_name));

        gameScreen = screen;
        gameScreen.setDimensionListener(new Runnable()
        {
            @Override
            public void run()
            {
                viewHasDimension = true;
                if (wantToLaunch)
                {
                    launch();
                }
            }
        });
        gameScreen.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                v.performClick();
                // Only use touch event if not paused TODO: maybe change this behavior? the idea is that pause menu handled by ui thread, bad idea?
                if (isPlaying())
                {
                    synchronized (updateAndFrameAndInputMonitor)
                    {
                        onTouchEvent(event);
                        return true;
                    }
                } //TODO: join() this thread in stop, and for pause make this part of countdown latch, and wait this thread to make sure that it is done before pause happens? likely not necessary... simply have the child not do stuff with inputs after it ends the game, and the occasional input after a puase makes no diff
                // TODO: and its not even a thread, usually just hijacks off main...
                // TODO: so make sure its only called from main thread? possibly needed?
                return false;
            }
        });
    }

    public void startGame()
    {
        wantToLaunch = true;
        if (viewHasDimension)
        {
            launch();
        }
    }

    private void launch()
    {
        wantToLaunch = false;
        if (started)
        {
            // May not start a game that is already started
            throw new IllegalStateException("Game already started!");
        }
        else
        {
            started = true;

            // At this point, the surfaceView (and thus the game) has dimensions, so we can do initialization based on them.
            initialize();

            // We will launch two threads.
            // 1) Do game logic
            // 2) Update surface view

            updateThread.start();
            frameThread.start();
        }
    }

    /**
     * Called once before updates and frames begin.
     * In this call, we are guaranteed that the surfaceViiew (and the game) has dimensions
     * So do any initialization that involves getGameWidth/getGameHeight.
     */
    protected abstract void initialize(); //Always called once before update() calls first begin

    private void runUpdates()
    {
        Looper.prepare();
        final Handler updateHandler = new Handler();

        Runnable updateCallback = new Runnable()
        {
            long startTime = 0;
            @Override
            public void run()
            {
                Log.d("ela", String.valueOf(System.nanoTime() - startTime));
                // Keep track of what time it is now. Goes first to get most accurate timing.
                startTime = System.nanoTime();

                // Schedule next update. Goes second to get as accurate a possible updates.
                updateHandler.removeCallbacksAndMessages(null);
                updateHandler.postDelayed(this, expectedUpdateTimeMS); //TODO: inject stutter updates to test various drawing schemes

                // Stop thread.
                if (stopped)
                {
                    updateHandler.removeCallbacksAndMessages(null);
                    Looper.myLooper().quit();
                    return;
                }

                synchronized (updateAndFrameAndInputMonitor)
                {
                    L.d("Update V", "thread");

                    // Do a game update.
                    update();

                    // Save game state
                    GameState gameState = new GameState();
                    saveGameState(gameState);
                    gameState.timeStamp = startTime;
                    gameStates.add(gameState);

                    // Pause game (postDelayed runnable should not run while this thread is waiting, so no issues there)
                    while (paused)
                    {
                        try
                        {
                            if (!updateThreadPaused)
                            {
                                pauseLatch.countDown();
                            }
                            updateThreadPaused = true;
                            updateAndFrameAndInputMonitor.wait();
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }
                    updateThreadPaused = false;
                    L.d("Update ^", "thread");
                }
            }
        };

        updateHandler.post(updateCallback);
        Looper.loop();
    }

    private void runFrames()
    {
        Looper.prepare();
        vsync = Choreographer.getInstance();
        //final Handler viewPaintThreadHandler = new Handler();
        Choreographer.FrameCallback callback = new Choreographer.FrameCallback()
        {
            @Override
            public void doFrame(long frameTimeNanos)
            {
                // Must ask for new callback each frame!
                // We ask at the start because the Choreographer automatically
                // skips frames for us if we don't draw fast enough,
                // and it will make a Log.i to let us know that it skipped frames (so we know)
                // If we move it to the end we essentially manually skip frames,
                // but we won't know that an issue occurred.
                vsync.postFrameCallback(this);

                // Acquire the canvas lock now to save time later (this can be an expensive operation!
                if (gameScreen.canVisualize() && !gameScreen.preparedToVisualize())
                {
                    gameScreen.prepareVisualize();
                }

                // Correct for minor difference in vsync time.
                // This is probably totally unnecessary. (And will only change frameTimeNanos in a sufficiently high API anyway)
                frameTimeNanos -= vsyncOffsetNanos;

                synchronized (updateAndFrameAndInputMonitor)
                {
                    L.d("Frame V", "thread");

                    // If we aren't prepared to visualize yet, try one more time, now that we have acquired the monitor lock (because this may be taking place a good deal later)
                    if (!gameScreen.preparedToVisualize() && gameScreen.canVisualize())
                    {
                        gameScreen.prepareVisualize();
                    }

                    // Stop game if prompted
                    if (stopped)
                    {
                        // Since we asked for the callback up above,
                        // we should remove it if we plan on quiting
                        // so we do not do an extra callback.
                        vsync.removeFrameCallback(this);
                        if (gameScreen.preparedToVisualize())
                        {
                            if (lastVisualizedGameState != null)
                            {
                                gameScreen.visualize(lastVisualizedGameState);
                            }
                            else
                            {
                                // Unlock the canvas without posting anything.
                                gameScreen.unlockCanvasAndClear();
                            }
                        }
                        Looper.myLooper().quit();
                        return;
                    }

                    // Pause game
                    // Spin lock when we want to pause
                    while (paused && !oneFrameThenPause)
                    {
                        try
                        {
                            // Do not count down the latch off spurious wakeup!
                            if (!frameThreadPaused)
                            {
                                pauseLatch.countDown();
                            }
                            frameThreadPaused = true;
                            updateAndFrameAndInputMonitor.wait();
                            L.d("WOKEN", "WOKEN");
                        }
                        catch (InterruptedException e)
                        {
                            e.printStackTrace();
                        }
                    }
                    frameThreadPaused = false;

                    boolean paintedFrame = false;

                    // Paint frame
                    if (gameScreen.preparedToVisualize())
                    {
                        GameState oldState;
                        GameState newState;

                        while (true)
                        {
                            L.d("GameStates: " + gameStates.size(), "new");
                            if (gameStates.size() >= 2) // We need two states to draw (interpolate between them)
                            {
                                // Get the first two saved states
                                oldState = gameStates.get(0);
                                newState = gameStates.get(1);

                                // Time that passed between the game states in question.
                                //long timeBetween = newState.timeStamp - oldState.timeStamp;

                                // Interpolate based on time that has past since the second active game state
                                // as a fraction of the time between the two active states.
                                //double interpolationRatio = (frameTimeNanos - newState.timeStamp) / ((double) timeBetween);
                                double interpolationRatio = (frameTimeNanos - newState.timeStamp) / ((double) expectedUpdateTimeNS);

                                // If we are up to the new update, remove the old one as it is not needed.
                                if (interpolationRatio >= 1)
                                {
                                    L.d("Removing a state we have used up", "new");
                                    // Remove the old update.
                                    if (gameStates.size() >= 1)
                                    {
                                        //gameStates.get(0).cleanup();
                                        gameStates.remove(0);
                                    }
                                    continue;
                                }
                                else
                                {
                                    Map<InterpolatableEntity, InterpolatableState> oldInterpolatableEntitiesMap = oldState.getInterpolatableEntitiesMap();
                                    Map<InterpolatableEntity, InterpolatableState> newInterpolatableEntitiesMap = newState.getInterpolatableEntitiesMap();

                                    Set<Map.Entry<InterpolatableEntity, InterpolatableState>> newInterpolatableEntitiesMapEntrySet = newInterpolatableEntitiesMap.entrySet();

                                    for (Map.Entry<InterpolatableEntity, InterpolatableState> newEntry : newInterpolatableEntitiesMapEntrySet)
                                    {
                                        InterpolatableEntity key = newEntry.getKey(); // We can define this now because only on rare occasion will this not be used.
                                        // If this entry is common to both sets
                                        if (oldInterpolatableEntitiesMap.containsKey(key))
                                        {
                                            InterpolatableState newValue = newEntry.getValue();
                                            InterpolatableState oldValue = oldInterpolatableEntitiesMap.get(key);

                                            double interpolatedX = ((newValue.x - oldValue.x) * interpolationRatio) + oldValue.x;
                                            double interpolatedY = ((newValue.y - oldValue.y) * interpolationRatio) + oldValue.y;

                                            //TODO: maybe find another way to communicate where to paint the entity?
                                            key.interpolatedX = interpolatedX;
                                            key.interpolatedY = interpolatedY;

                                            key.interpolatedThisFrame = true;
                                        }
                                        else
                                        {
                                            key.interpolatedThisFrame = false;
                                        }
                                    }

                                    gameScreen.visualize(newState);
                                    paintedFrame = true;
                                    lastVisualizedGameState = newState;
                                    break;
                                }
                            }
                            else
                            {
                                Log.w("T", "We want to draw but there aren't enough new updates!");
                                if (lastVisualizedGameState != null)
                                {
                                    gameScreen.visualize(lastVisualizedGameState);
                                    paintedFrame = true;
                                    Log.w("T", " Using previous valid state.");
                                }
                                break;
                            }
                        }
                    }

                    if (paintedFrame)
                    {
                        oneFrameThenPause = false;
                    }
                    L.d("Frame ^", "thread");
                }
            }
        };
        vsync.postFrameCallback(callback);
        Looper.loop();
    }


    /**
     * Stop update and frame threads.
     * After the threads finish their current loop execution,
     * The game will then call the subclasses cleanup.
     */
    public void stopGame()
    {
        if (!Thread.currentThread().getName().equals("main"))
        {
            Log.w("T", "Was not called from the main thread, instead from: " + Thread.currentThread().getName() + ". Will be run from the Main thread instead.");
        }

        stopped = true; //TODO: synchronize this?

        Handler mainHandler = new Handler(appContext.getMainLooper());
        mainHandler.post(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    updateThread.join();
                    frameThread.join();
                    L.d("Two threads stopped", "stop");
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                finally
                {
                    L.d("Finally running", "stop");
                    cleanup();
                    dispatchEvent(GameEventConstants.GAME_OVER);
                }
            }
        });
    }

    /**
     * Cleanup and garbage collect here, which will run before the superclass cleans.
     */
    @SuppressLint("MissingSuperCall") //Because this is the top level implementor
    public void cleanup()
    {
        Log.d("h", "CLEANUP");
        vsync = null;
        random = null;
        rumble = null;
        updateThread = null;
        frameThread = null;

        //Cleanup gameScreen TODO: more
        gameScreen.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                v.performClick();
                return false;
            }
        });
    }

    /**
     * Pause the threads
     */
    public void pauseGame() //TODO: should only be called from main thread?
    {
        if (Thread.currentThread().equals(updateThread) || Thread.currentThread().equals(frameThread))
        {
            paused = true;
            return;
        }
        if (!isActive())
        {
            Log.w("F", "No need to pause game that isn't running.");
            return;
        }/*
        Handler handler = new Handler(appContext.getMainLooper());
        handler.post(new Runnable()
        {
            @Override
            public void run()
            {
                Log.d("M", Thread.currentThread().getName());
                paused = true; //Tell threads to pause
                Log.d("M", "Abstract Game Engine attempt to pause threads.");
                while (!frameThreadPaused && !updateThreadPaused);
                Log.d("M", "Abstract Game Engine sees threads paused.");
            }
        });*/

        // 1 frame thread + 1 update thread = 2
        pauseLatch = new CountDownLatch(2);

        // Tell threads to stop
        paused = true;

        try
        {
            pauseLatch.await();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

    public void unpauseGame()
    {
        if (!isActive())
        {
            Log.w("F", "Cannot unpause game that isn't active.");
            return;
        }
        Log.d("G", "Unpausing game");
        paused = false;
        synchronized (updateAndFrameAndInputMonitor)
        {
            updateAndFrameAndInputMonitor.notifyAll();
        }
    }

    /**
     * When the app resumes and the game is paused, the surface view will have cleared.
     * Use this function to paint a single new frame onto the view if you are not unpausing right away.
     */
    public void paintOneFrame()
    {
        synchronized (updateAndFrameAndInputMonitor)
        {
            if (isPlaying())
            {
                throw new IllegalStateException("Game must be paused first!");
            }

            // Set the flag which allows the frame thread to escape from pause for one frame
            oneFrameThenPause = true;

            // Wakeup the frame thread.
            // We use notify all to make sure the frame thread gets woken.
            // The update thread will immediately wait() because the game is still paused.
            updateAndFrameAndInputMonitor.notifyAll();
        }
    }

    public boolean isActive()
    {
        return started && !stopped;
    }

    public boolean isPlaying()
    {
        return isActive() && !paused;
    }

    protected abstract void update();

    protected abstract void saveGameState(GameState gameState);

    /**
     * Tracks an entity for interpolation purposes and returns it
     * TODO: remove this and replace with game states (a little easier to deal with, but not much
     */
    /*
    protected <T extends InterpolatableEntity> T track(T entityKind)
    {
        entityList.add(entityKind);
        return entityKind;
    }*/

    /**
     * Black magic or utter genius?
     * Perhaps add overloaded constructor for InterpolatableEntity itself that does not require you to pass InterpolatableEntity.class?
     */
    /*
    protected <T extends InterpolatableEntity> T createEntity(Class<T> type, Object... args)
    {
        T creation = null;

        // Find the desired constructor by analysing the args that were passed
        Constructor[] constructors = type.getDeclaredConstructors();
        Constructor matchedConstructor = null;
        for (Constructor constructor : constructors)
        {
            boolean matchWorks = true;
            Class[] conArgTypes = constructor.getParameterTypes();
            if (args.length != conArgTypes.length)
            {
                matchWorks = false;
                Log.d("A", "Wrong length");
            }
            else
            {
                for (int i = 0; i < args.length; i++)
                {
                    Class argType = args[i].getClass();
                    Class conArgType = conArgTypes[i];

                    if (argType.isPrimitive() && conArgType.isPrimitive()) //This check does not work on wrapper classes. Needs a fix!
                    {
                        Log.d("A", "Both primitive");
                    }
                    else if (!(conArgType.isAssignableFrom(argType)))
                    {
                        matchWorks = false;
                        Log.d("A", "Arg mismatch. constructor wanted: " + conArgType + ". arg supplied: " + argType);
                        break;
                    }
                }
            }
            if (matchWorks)
            {
                matchedConstructor = constructor;
            }
        }
        if (matchedConstructor == null)
        {
            throw new IllegalArgumentException("No constructor found matching the supplied arguments.");
        }

        try
        {
            Log.d("h", "Class exists" + type.getDeclaredConstructors()[0].toString());
            creation = type.cast(matchedConstructor.newInstance(args));
            entityList.add(creation);
        }
        catch (InstantiationException e)
        {
            e.printStackTrace();
        }
        catch (IllegalAccessException e)
        {
            e.printStackTrace();
        }
        catch (InvocationTargetException e)
        {
            e.printStackTrace();
        }
        return creation;
    }*/

    public int getGameWidth()
    {
        return gameScreen.getWidth();
    }
    public int getGameHeight()
    {
        return gameScreen.getHeight();
    }

    public abstract void onTouchEvent(MotionEvent event);
}