/**
 * Copyright (C) 2016-2019 Salvatore Virga - salvo.virga@tum.de, Marco Esposito - marco.esposito@tum.de
 * Technische Universit�t M�nchen Chair for Computer Aided Medical Procedures and Augmented Reality Fakult�t
 * f�r Informatik / I16, Boltzmannstra�e 3, 85748 Garching bei M�nchen, Germany http://campar.in.tum.de All
 * rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided
 * that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
 * following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and
 * the following disclaimer in the documentation and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package de.tum.in.camp.kuka.ros;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

import org.ros.exception.ParameterNotFoundException;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.parameter.ParameterTree;
import org.ros.time.NtpTimeProvider;
import org.ros.time.TimeProvider;
import org.ros.time.WallTimeProvider;

import com.kuka.roboticsAPI.applicationModel.IApplicationData;
import com.kuka.roboticsAPI.uiModel.IApplicationUI;
import com.kuka.roboticsAPI.uiModel.userKeys.IUserKey;
import com.kuka.roboticsAPI.uiModel.userKeys.IUserKeyBar;
import com.kuka.roboticsAPI.uiModel.userKeys.IUserKeyListener;
import com.kuka.roboticsAPI.uiModel.userKeys.UserKeyAlignment;
import com.kuka.roboticsAPI.uiModel.userKeys.UserKeyEvent;

/**
 * Utility class to get configuration at startup from the config.txt file or from ROS params in the ROS param
 * server.
 */
public class Configuration extends AbstractNodeMain {

  // Name to use to build the name of the ROS topics
  private String robotName;
  private String masterIp;
  private String masterPort;
  private String masterUri; // < IP address of ROS core to talk to.
  private String robotIp;
  private boolean configurationSuccessful = false;
  private boolean ntpWithHost;

  private TimeProvider timeProvider;
  private ScheduledExecutorService ntpExecutorService = null;

  private ConnectedNode node;
  private ParameterTree params;

  // It is used to wait until we are connected to the ROS master and parameters
  // are available.
  private Semaphore initSemaphore = new Semaphore(0);

  private IApplicationData applicationData;

  public Configuration(IApplicationData data) {
    applicationData = data;
    checkConfiguration();
  }

  public void checkConfiguration() {
    if (!configurationSuccessful) {
      configure();
      if (!configurationSuccessful) { throw new RuntimeException("Configuration was not successful"); }
    }
  }

  private void configure() {
    robotName = applicationData.getProcessData("robot_name").getValue();
    robotIp = applicationData.getProcessData("robot_ip").getValue();

    // Check if NTP Server is used or not.
    ntpWithHost = applicationData.getProcessData("ntp").getValue();

    // Obtain IP and port of the ROS Master
    masterIp = applicationData.getProcessData("master_ip").getValue();
    masterPort = applicationData.getProcessData("master_port").getValue();
    masterUri = "http://" + masterIp + ":" + masterPort;

    configurationSuccessful = true;
  }

  /**
   * Get the ROS Master URI, obtained from the configuration file. Format : http://IP:port
   * 
   * @return ROS Master URI
   * @throws URISyntaxException
   */
  public URI getMasterURI() throws URISyntaxException {
    checkConfiguration();
    return new URI(masterUri);
  }

  /**
   * Get the ROS Master IP address, obtained from the configuration file.
   * 
   * @return ROS Master IP address
   */
  public String getMasterIp() {
    checkConfiguration();
    return masterIp;
  }

  /**
   * Get the robot IP address, obtained from the configuration file.
   * 
   * @return Robot IP address
   */
  public String getRobotIp() {
    checkConfiguration();
    return robotIp;
  }

  /**
   * Get the robot name, obtained from the configuration file.
   * 
   * @return name of the robot
   */
  public String getRobotName() {
    checkConfiguration();
    return robotName;
  }

  /**
   * Return if an external NTP server should be used, value obtained from the configuration file.
   * 
   * @return true if external NTP server should be used
   */
  public boolean getShouldUseNtp() {
    checkConfiguration();
    return ntpWithHost;
  }

  /**
   * @see org.ros.node.NodeMain#getDefaultNodeName()
   */
  @Override
  public GraphName getDefaultNodeName() {
    return GraphName.of(robotName + "/configuration");
  }

  /**
   * This method is called when the <i>execute</i> method from a <i>nodeMainExecutor</i> is called.<br>
   * Do <b>NOT</b> manually call this.
   * <p>
   * 
   * @see org.ros.node.AbstractNodeMain#onStart(org.ros.node.ConnectedNode)
   */
  @Override
  public void onStart(final ConnectedNode connectedNode) {
    node = connectedNode;
    Logger.setRosLogger(node.getLog());
    initSemaphore.release();
  }

  /**
   * Wait for ROS Master to connect.
   * 
   * @throws InterruptedException
   */
  public void waitForInitialization() throws InterruptedException {
    initSemaphore.acquire();
  }

  private ParameterTree getParameterTree() {
    if (initSemaphore.availablePermits() > 0) {
      Logger.warn("waitForInitialization not called before using parameters!");
    }
    if (node == null) { return null; }
    return node.getParameterTree();
  }

  /**
   * Get the default relative joint speed for the robot, reading <b>defaultRelativeJointSpeed</b> from the ROS
   * parameter server.
   * 
   * @return the default relative joint speed
   */
  public Double getDefaultRelativeJointVelocity() {
    Double defaultRelativeJointVelocity = getDoubleParameter("defaultRelativeJointSpeed");
    if (defaultRelativeJointVelocity == null) {
      defaultRelativeJointVelocity = 0.5;
    }
    return defaultRelativeJointVelocity;
  }

  /**
   * Get the default relative joint acceleration for the robot, reading <b>defaultRelativeJointSpeed</b> from
   * the ROS parameter server.
   * 
   * @return the default relative joint acceleration
   */
  public Double getDefaultRelativeJointAcceleration() {
    Double defaultRelativeJointAcceleration = getDoubleParameter("defaultRelativeJointAcceleration");
    if (defaultRelativeJointAcceleration == null) {
      defaultRelativeJointAcceleration = 1.0;
    }
    return defaultRelativeJointAcceleration;
  }

  /**
   * Get the name of the tool to use, reading <b>toolName</b> from the ROS parameter server..
   * 
   * @return name of the tool
   */
  public String getToolName() {
    String toolName = getStringParameter("toolName");
    if (toolName == null) {
      toolName = "";
    }
    return toolName;
  }

  /**
   * Get the id of the endpoint frame to use, reading <b>endpointFrame</b> from the ROS parameter server.
   * 
   * @return id of the frame
   */
  public String getEndpointFrame() {
    String endpointFrame = getStringParameter("endpointFrame");
    if (endpointFrame == null) {
      endpointFrame = "";
    }
    return endpointFrame;
  }

  /**
   * Get if <i>joint_state</i> shoud be published, reading <b>publishJointStates</b> from the ROS parameter
   * server.
   * 
   * @return true if <i>joint_state</i>
   */
  public boolean getPublishJointStates() {
    Boolean publishStates = getBooleanParameter("publishJointStates");
    if (publishStates == null) {
      publishStates = false;
    }
    return publishStates;
  }

  /**
   * Get the default relative joint speed for the robot, reading <b>defaultRelativeJointSpeed</b> from the ROS
   * parameter server.
   * 
   * @return the default relative joint speed
   */
  public Boolean getEnforceMessageSequence() {
    Boolean enforceMessageSequence = getBooleanParameter("enforceMessageSequence");
    if (enforceMessageSequence == null) {
      enforceMessageSequence = false;
    }
    return enforceMessageSequence;
  }

  /**
   * Get the minimum trajectory execute time for SmartServo object, reading <b>minTrajExecTime</b> from the
   * ROS parameter server.
   * 
   * @return the min trajectory exectute time
   */
  public Double getMinTrajExecTime() {
    Double minTrajExecTime = getDoubleParameter("minTrajExecTime");
    if (minTrajExecTime == null) {
      minTrajExecTime = 0.1;
    }
    return minTrajExecTime;
  }

  /**
   * Get the timeout after goal reached for SmartServo, reading <b>timeoutAfterGoalReach</b> from the ROS
   * parameter server.
   * 
   * @return the timeout after goal reached
   */
  public Double getTimeoutAfterGoalReach() {
    Double timeoutAfterGoalReach = getDoubleParameter("timeoutAfterGoalReach");
    if (timeoutAfterGoalReach == null) {
      timeoutAfterGoalReach = 3600.0;
    }
    return timeoutAfterGoalReach;
  }

  /**
   * Get the maximum translational velocity the robot should have, reading <b>maxTranslationVelocity</b> from
   * the ROS parameter server.
   * 
   * @return
   */
  @SuppressWarnings("unchecked")
  public double[] getMaxTranslationVelocity() {
    // Attempt to fill a List with the values from the parameters server, if something goes wrong we generate an empty list
    List<Double> maxTranslationVelocityList = null;
    try {
      maxTranslationVelocityList = (List<Double>) getListParameter("maxTranslationVelocityList");
    }
    catch (ClassCastException e) {
      maxTranslationVelocityList = new ArrayList<Double>();
    }
    
    double[] maxTranslationVelocity = new double[3];

    if (maxTranslationVelocityList == null) {
      Arrays.fill(maxTranslationVelocity, 1000.0); // Setting the default value.
    }
    else if (maxTranslationVelocityList.size() != 3) {
      Logger.warn("The ROS parameter 'maxTranslationalVelocity' has to have 3 components, using its default values.");
      Arrays.fill(maxTranslationVelocity, 1000.0); // Setting the default value.
    }
    // At this point the List is not null and has 3 elements.
    else {
      for (int i = 0; i < maxTranslationVelocityList.size(); i++) {
        maxTranslationVelocity[i] = maxTranslationVelocityList.get(i);
      }
    }
    return maxTranslationVelocity;
  }

  /**
   * Get the maximum orientation velocity the robot should have, reading <b>maxOrientationVelocity</b> from
   * the ROS parameter server.
   * 
   * @return
   */
  @SuppressWarnings("unchecked")
  public double[] getmaxOrientationVelocity() {
    
    // Attempt to fill a List with the values from the parameters server, if something goes wrong we generate an empty list
    List<Double> maxOrientationVelocityList = null;
    try {
      maxOrientationVelocityList = (List<Double>) getListParameter("maxOrientationVelocity");
    }
    catch (ClassCastException e) {
      maxOrientationVelocityList = new ArrayList<Double>();
    }
    
    double[] maxOrientationVelocity = new double[3];

    if (maxOrientationVelocityList == null) {
      Arrays.fill(maxOrientationVelocity, 0.5); // Setting the default value.
    }
    else if (maxOrientationVelocityList.size() != 3) {
      Logger.warn("The ROS parameter 'maxOrientationVelocity' has to have 3 components of type double, using its default values.");
      Arrays.fill(maxOrientationVelocity, 0.5); // Setting the default value.
    }
    // At this point the List is not null and has 3 elements.
    else {
      for (int i = 0; i < maxOrientationVelocityList.size(); i++) {
        maxOrientationVelocity[i] = maxOrientationVelocityList.get(i);
      }
    }
    return maxOrientationVelocity;
  }

  /**
   * Get the Cartesian acceleration the robot should have, reading <b>cartesianAcceleration</b> from the ROS
   * parameter server.
   * 
   * @return
   */
  public Double getCartesianAcceleration() {
    Double cartesianAcceleration = getDoubleParameter("cartesianAcceleration");
    if (cartesianAcceleration == null) {
      cartesianAcceleration = 200.0;
    }
    return cartesianAcceleration;
  }

  /**
   * Get the orientation acceleration the robot should have, reading <b>orientationAcceleration</b> from the
   * ROS parameter server.
   * 
   * @return
   */
  public Double getOrientationAcceleration() {
    Double orientationAcceleration = getDoubleParameter("orientationAcceleration");
    if (orientationAcceleration == null) {
      orientationAcceleration = 0.05;
    }
    return orientationAcceleration;
  }

  /**
   * Get the Cartesian jerk the robot should have, reading <b>cartesianJerk</b> from the ROS parameter server.
   * 
   * @return
   */
  public Double getCartesianJerk() {
    Double cartesianJerk = getDoubleParameter("cartesianJerk");
    if (cartesianJerk == null) {
      cartesianJerk = 50.0;
    }
    return cartesianJerk;
  }

  /**
   * Get the time provider to use, this is selected accordingly to the value of <b>ntp_with_host</b> in the
   * configuration file.
   * 
   * @return the time provider to use, NtpTimeProvider or WallTimeProvider
   */
  public TimeProvider getTimeProvider() {
    if (timeProvider == null) setupTimeProvider();
    return timeProvider;
  }

  /**
   * Configure the time provider to use accordingly to the value of of <b>ntp_with_host</b> in the
   * configuration file.
   * 
   * @return the configured time provider
   */
  private TimeProvider setupTimeProvider() {
    checkConfiguration();
    if (ntpWithHost) {
      try {
        ntpExecutorService = Executors.newScheduledThreadPool(1);
        NtpTimeProvider provider = new NtpTimeProvider(InetAddress.getByName(masterIp), ntpExecutorService);
        timeProvider = provider;
      }
      catch (UnknownHostException e) {
        Logger.error("Could not setup NTP time provider!");
      }
    }
    else {
      timeProvider = new WallTimeProvider();
    }

    return timeProvider;
  }

  /**
   * Simple class to handle toolbar settings
   */
  private class ToolbarSpecification {
    public String name;
    public String[] buttonIDs;
  }

  /**
   * TODO : @Marco doc
   * 
   * @param appUI
   * @param publisher
   * @param generalKeys
   * @param generalKeyLists
   * @param generalKeyBars
   */
  public void setupToolbars(IApplicationUI appUI, final iiwaPublisher publisher, List<IUserKey> generalKeys, List<IUserKeyListener> generalKeyLists,
      List<IUserKeyBar> generalKeyBars) {
    List<ToolbarSpecification> ts = getToolbarSpecifications();
    if (ts != null) {
      for (final ToolbarSpecification t : ts) {
        IUserKeyBar generalKeyBar = appUI.createUserKeyBar(t.name);

        for (int i = 0; i < t.buttonIDs.length; i++) {
          final String buttonID = t.buttonIDs[i];
          IUserKey generalKey;
          if (buttonID.contains(",")) {
            // double button
            final String[] singleButtonIDs = buttonID.split(",");

            IUserKeyListener generalKeyList = new IUserKeyListener() {
              @Override
              public void onKeyEvent(IUserKey key, com.kuka.roboticsAPI.uiModel.userKeys.UserKeyEvent event) {
                if (event == UserKeyEvent.FirstKeyDown) {
                  publisher.publishButtonPressed(t.name + "_" + singleButtonIDs[0]);
                }
                else if (event == UserKeyEvent.FirstKeyUp) {
                  publisher.publishButtonReleased(t.name + "_" + singleButtonIDs[0]);
                }
                else if (event == UserKeyEvent.SecondKeyDown) {
                  publisher.publishButtonPressed(t.name + "_" + singleButtonIDs[1]);
                }
                else if (event == UserKeyEvent.SecondKeyUp) {
                  publisher.publishButtonReleased(t.name + "_" + singleButtonIDs[1]);
                }
              }
            };
            generalKeyLists.add(generalKeyList);

            generalKey = generalKeyBar.addDoubleUserKey(i, generalKeyList, false);
            generalKey.setText(UserKeyAlignment.TopMiddle, singleButtonIDs[0]);
            generalKey.setText(UserKeyAlignment.BottomMiddle, singleButtonIDs[1]);
            generalKeys.add(generalKey);
          }
          else {
            // single button
            IUserKeyListener generalKeyList = new IUserKeyListener() {
              @Override
              public void onKeyEvent(IUserKey key, com.kuka.roboticsAPI.uiModel.userKeys.UserKeyEvent event) {
                if (event == UserKeyEvent.KeyDown) {
                  publisher.publishButtonPressed(t.name + "_" + buttonID);
                }
                else if (event == UserKeyEvent.KeyUp) {
                  publisher.publishButtonReleased(t.name + "_" + buttonID);
                }
              }
            };
            generalKeyLists.add(generalKeyList);

            generalKey = generalKeyBar.addUserKey(i, generalKeyList, false);
            generalKey.setText(UserKeyAlignment.TopMiddle, buttonID);
            generalKeys.add(generalKey);
          }
        }

        generalKeyBars.add(generalKeyBar);
      }
      for (IUserKeyBar kb : generalKeyBars)
        kb.publish();
    }
  }

  /**
   * Get the toolbar configuration to build buttons on the SmartPad from the param
   * <b>toolbarSpecifications</b> in the ROS param server.
   * 
   * @return the toolbar specifications
   */
  public List<ToolbarSpecification> getToolbarSpecifications() {
    List<ToolbarSpecification> ret = new ArrayList<ToolbarSpecification>();
    List<?> rawParam = getListParameter("toolbarSpecifications");

    if (rawParam == null) { return null; }

    @SuppressWarnings("unchecked")
    List<String> stringParam = new LinkedList<String>((Collection<? extends String>) rawParam);

    while (stringParam.size() > 0 && (stringParam.get(0)).equals("spec")) {
      ToolbarSpecification ts = new ToolbarSpecification();
      stringParam.remove(0);
      ts.name = (String) stringParam.get(0);
      stringParam.remove(0);
      List<String> buttons = new LinkedList<String>();
      while (stringParam.size() > 0 && !(stringParam.get(0)).equals("spec")) {
        buttons.add(stringParam.get(0));
        stringParam.remove(0);
      }
      // toolbar name but no buttons; TODO: log
      if (buttons.size() == 0) {
        continue;
      }
      ts.buttonIDs = buttons.toArray(new String[buttons.size()]);
      ret.add(ts);
    }

    return ret;
  }

  /**
   * Read a double param from the ROS param server given its name
   * 
   * @param argname rosparam name to get
   * @return a double
   */
  public Double getDoubleParameter(String argname) {
    params = getParameterTree();
    Double ret = null;
    if (params == null) { return null; }
    try {
      ret = params.getDouble(robotName + "/" + argname);
    }
    catch (ParameterNotFoundException e) {}

    return ret;
  }

  /**
   * Read a boolean param from the ROS param server given its name
   * 
   * @param argname rosparam name to get
   * @return a boolean
   */
  public Boolean getBooleanParameter(String argname) {
    params = getParameterTree();
    Boolean ret = null;
    if (params == null) { return null; }
    try {
      ret = params.getBoolean(robotName + "/" + argname);
    }
    catch (ParameterNotFoundException e) {}

    return ret;
  }

  /**
   * Read a string param from the ROS param server given its name
   * 
   * @param argname
   * @return a string
   */
  public String getStringParameter(String argname) {
    params = getParameterTree();
    String ret = null;
    if (params == null) { return null; }
    try {
      ret = params.getString(robotName + "/" + argname);
    }
    catch (ParameterNotFoundException e) {}

    return ret;
  }

  /**
   * Read a list param from the ROS param server given its name
   * 
   * @param argname
   * @return a list
   */
  public List<?> getListParameter(String argname) {
    List<?> args = new LinkedList<String>(); // supports remove
    params = getParameterTree();
    try {
      args = params.getList(robotName + "/" + argname);
      if (args == null) { return null; }
    }
    catch (ParameterNotFoundException e) {
      return null;
    }
    return args;
  }

  public void cleanup() {
    if (ntpWithHost) {
      try {
        ((NtpTimeProvider) timeProvider).stopPeriodicUpdates();
      }
      catch (NullPointerException e) {
        // can happen if there is an exception somewhere
      }
      if (ntpExecutorService != null) {
        ntpExecutorService.shutdownNow();
        ntpExecutorService = null;
      }
      Logger.info("Stopped NTP periodic updates.");
    }
  }

}
