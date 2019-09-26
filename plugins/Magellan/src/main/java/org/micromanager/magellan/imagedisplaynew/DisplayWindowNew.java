/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.micromanager.magellan.imagedisplaynew;

import org.micromanager.magellan.imagedisplaynew.events.MagellanNewImageEvent;
import org.micromanager.magellan.imagedisplaynew.events.ScrollersAddedEvent;
import com.google.common.eventbus.Subscribe;
import ij.gui.ImageWindow;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Image;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.micromanager.magellan.imagedisplaynew.events.DisplayClosingEvent;
import org.micromanager.magellan.misc.Log;
import org.micromanager.magellan.surfacesandregions.MultiPosGrid;
import org.micromanager.magellan.surfacesandregions.SurfaceInterpolator;

/**
 *
 * @author henrypinkard
 */
class DisplayWindowNew implements WindowListener {

   //from other window
   private static final int MOUSE_WHEEL_ZOOM_INTERVAL_MS = 100;
   private static final int DELETE_SURF_POINT_PIXEL_TOLERANCE = 10;
   public static final int NONE = 0, EXPLORE = 1, SURFACE_AND_GRID = 2;
   private static final double ZOOM_FACTOR_MOUSE = 1.4;
   private static final double ZOOM_FACTOR_KEYS = 2.0;

   //all these are volatile because they are accessed by overlayer
   private volatile Point mouseDragStartPointLeft_, mouseDragStartPointRight_, currentMouseLocation_;
   private volatile Point exploreStartTile_, exploreEndTile_;
   private long lastMouseWheelZoomTime_ = 0;
   private boolean exploreAcq_ = false;
   private volatile int mode_ = NONE;
   private boolean mouseDragging_ = false;
   private long fullResPixelWidth_ = -1, fullResPixelHeight_ = -1; //used for scaling in fixed area acqs
   private int tileWidth_, tileHeight_;

   private MagellanCanvas imageCanvas_;
   private NewSubImageControls subImageControls_;
   private DisplayWindowControlsNew sideControls_;
   private JButton collapseExpandButton_;
   private JPanel leftPanel_, rightPanel_;

   private MagellanDisplayController display_;
   JFrame window_;

   public DisplayWindowNew(MagellanDisplayController display) {
      window_ = new JFrame();

      display_ = display;
      display_.registerForEvents(this);
      window_.setSize(1500, 700);
      window_.setVisible(true);
      window_.addWindowListener(this);
      buildInitialUI();
      setupMouseListeners();
      setupKeyListeners();
   }

   @Subscribe
   public void onDisplayClose(DisplayClosingEvent e) {
      //For some reason these two lines appear to be essential for preventing memory leaks after closing the display
      for (Component c : leftPanel_.getComponents()) {
         leftPanel_.remove(c);
      }
      for (Component c : rightPanel_.getComponents()) {
         rightPanel_.remove(c);
      }

      window_.removeWindowListener(this);
      display_.unregisterForEvents(this);
      display_ = null;
      collapseExpandButton_ = null;
      imageCanvas_ = null;
      subImageControls_ = null;
      sideControls_ = null;
      SwingUtilities.invokeLater(new Runnable() {
         @Override
         public void run() {
            window_.dispose();
            window_ = null;
         }
      });
   }

   @Subscribe
   public void onScrollersAdded(final ScrollersAddedEvent e
   ) {
      //New scrollbars have been made visible
      window_.revalidate();
   }

   public void updateExploreZControls(int zIndex) {
      subImageControls_.updateExploreZControls(zIndex);
   }

   private void buildInitialUI() {
      window_.setLayout(new BorderLayout());

      imageCanvas_ = new MagellanCanvas(display_);
      subImageControls_ = new NewSubImageControls(display_, display_.getZStep(), display_.isExploreAcquisiton());
      //TODO add channels for explore acquisitions

      leftPanel_ = new JPanel(new BorderLayout());
      leftPanel_.add(imageCanvas_.getCanvas(), BorderLayout.CENTER);
      leftPanel_.add(subImageControls_, BorderLayout.PAGE_END);
      window_.add(leftPanel_, BorderLayout.CENTER);

      DisplayWindowControlsNew sideControls = new DisplayWindowControlsNew(display_, null);
      sideControls_ = sideControls;
      JPanel buttonPanel = new FixedWidthJPanel();
      collapseExpandButton_ = new JButton();

//      TextIcon t1 = new TextIcon(collapseExpandButton_, "Hide controls", TextIcon.Layout.HORIZONTAL);
//      t1.setFont(t1.getFont().deriveFont(15.0f));
//      RotatedIcon r1 = new RotatedIcon(t1, RotatedIcon.Rotate.DOWN);
//      collapseExpandButton_.setIcon(r1);
      collapseExpandButton_.addActionListener(new ActionListener() {
         @Override
         public void actionPerformed(ActionEvent e) {
            boolean controlsExpanded = !sideControls.isVisible();
//            "\u25c4" : "\u25ba" //left and right arrow
//            t1.setText(controlsExpanded ? "Hide controls" : "Show controls");
            collapseOrExpandSideControls(controlsExpanded);
         }
      });
      buttonPanel.add(collapseExpandButton_, BorderLayout.CENTER);
      rightPanel_ = new JPanel(new BorderLayout());
      rightPanel_.add(buttonPanel, BorderLayout.CENTER);
      rightPanel_.add(sideControls_, BorderLayout.LINE_END);
      window_.add(rightPanel_, BorderLayout.LINE_END);

      window_.revalidate();

   }

   public void applyDisplaySettings(org.micromanager.display.DisplaySettings adjustedSettings) {

   }

   public void collapseOrExpandSideControls(boolean expand) {
      sideControls_.setVisible(expand);
      window_.revalidate();
   }

   /**
    * CAlled on EDT. Update image and make sure scrollers are in right positions
    *
    * @param images
    */
   void displayImage(Image image, MagellanDataViewCoords view) {
      //Make scrollbars reflect image
      subImageControls_.updateScrollerPositions(view);
      imageCanvas_.updateDisplayImage(image, view.getDisplayScaleFactor());

      imageCanvas_.getCanvas().repaint();
   }

   public void expandDisplayedRangeToInclude(List<MagellanNewImageEvent> newIamgeEvents) {
      subImageControls_.expandDisplayedRangeToInclude(newIamgeEvents);
   }

   @Override
   public void windowOpened(WindowEvent e) {
   }

   @Override
   public void windowIconified(WindowEvent e) {
   }

   @Override
   public void windowDeiconified(WindowEvent e) {
   }

   @Override
   public void windowActivated(WindowEvent e) {
   }

   @Override
   public void windowDeactivated(WindowEvent e) {
   }

   @Override
   //Invoked when the user attempts to close the window from the window's system menu.
   public void windowClosing(WindowEvent e) {
      display_.requestToClose();
   }

   @Override
   public void windowClosed(WindowEvent e) {
   }

   private void setupKeyListeners() {
      window_.addFocusListener(new FocusListener() {
         @Override
         public void focusGained(FocusEvent e) {
            imageCanvas_.getCanvas().requestFocus(); //give focus to canvas so keylistener active
         }

         @Override
         public void focusLost(FocusEvent e) {
         }
      });
      KeyListener kl = new KeyListener() {

         @Override
         public void keyTyped(KeyEvent ke) {
            if (ke.getKeyChar() == '=') {
               display_.zoom(1 / ZOOM_FACTOR_KEYS, null);
            } else if (ke.getKeyChar() == '-') {
               display_.zoom(ZOOM_FACTOR_KEYS, null);
            }
         }

         @Override
         public void keyPressed(KeyEvent ke) {
         }

         @Override
         public void keyReleased(KeyEvent ke) {
         }
      };

      //add keylistener to window and all subscomponenets so it will fire whenever
      //focus in anywhere in the window
      addRecursively(window_, kl);
   }

   private void addRecursively(Component c, KeyListener kl) {
      c.addKeyListener(kl);
      if (c instanceof Container) {
         for (Component subC : ((Container) c).getComponents()) {
            addRecursively(subC, kl);
         }
      }
   }

   private void setupMouseListeners() {

      imageCanvas_.getCanvas().addMouseWheelListener(new MouseWheelListener() {

         @Override
         public void mouseWheelMoved(MouseWheelEvent mwe) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastMouseWheelZoomTime_ > MOUSE_WHEEL_ZOOM_INTERVAL_MS) {
               lastMouseWheelZoomTime_ = currentTime;
               if (mwe.getWheelRotation() < 0) {
                  display_.zoom(1 / ZOOM_FACTOR_MOUSE, currentMouseLocation_); // zoom in?
               } else if (mwe.getWheelRotation() > 0) {
                  display_.zoom(ZOOM_FACTOR_MOUSE, currentMouseLocation_); //zoom out
               }
            }
         }
      });

      imageCanvas_.getCanvas().addMouseMotionListener(new MouseMotionListener() {

         @Override
         public void mouseDragged(MouseEvent e) {
            currentMouseLocation_ = e.getPoint();
            mouseDraggedActions(e);
         }

         @Override
         public void mouseMoved(MouseEvent e) {
            currentMouseLocation_ = e.getPoint();
            if (mode_ == EXPLORE) {
               display_.redrawOverlay();
            }
         }
      });

      imageCanvas_.getCanvas().addMouseListener(new MouseListener() {

         @Override
         public void mouseClicked(MouseEvent e) {
         }

         @Override
         public void mousePressed(MouseEvent e) {
            //to make zoom respond properly when switching between windows
            imageCanvas_.getCanvas().requestFocusInWindow();
            if (SwingUtilities.isRightMouseButton(e)) {
               //clear potential explore region
               exploreEndTile_ = null;
               exploreStartTile_ = null;
               mouseDragStartPointRight_ = e.getPoint();
            } else if (SwingUtilities.isLeftMouseButton(e)) {
               mouseDragStartPointLeft_ = e.getPoint();
            }
         }

         @Override
         public void mouseReleased(MouseEvent e) {
            mouseReleasedActions(e);
            mouseDragStartPointLeft_ = null;
            mouseDragStartPointRight_ = null;
         }

         @Override
         public void mouseEntered(MouseEvent e) {
            if (mode_ == EXPLORE) {
               display_.redrawOverlay();
            }
         }

         @Override
         public void mouseExited(MouseEvent e) {
            currentMouseLocation_ = null;
            if (mode_ == EXPLORE) {
               display_.redrawOverlay();
            }
         }
      });
   }

   private void mouseReleasedActions(MouseEvent e) {
      if (exploreAcq_ && mode_ == EXPLORE && SwingUtilities.isLeftMouseButton(e)) {
         Point p2 = e.getPoint();
         if (exploreStartTile_ != null) {
            //create events to acquire one or more tiles
            display_.acquireTiles(exploreStartTile_.y, exploreStartTile_.x, exploreEndTile_.y, exploreEndTile_.x);
            exploreStartTile_ = null;
            exploreEndTile_ = null;
         } else {
            //find top left row and column and number of columns spanned by drage event
            exploreStartTile_ = display_.getTileIndicesFromDisplayedPixel(mouseDragStartPointLeft_.x, mouseDragStartPointLeft_.y);
            exploreEndTile_ = display_.getTileIndicesFromDisplayedPixel(p2.x, p2.y);
         }
         display_.redrawOverlay();
      } else if (mode_ == SURFACE_AND_GRID && display_.getCurrentEditableSurfaceOrGrid() != null
              && display_.getCurrentEditableSurfaceOrGrid() instanceof SurfaceInterpolator
              && display_.isCurrentlyEditableSurfaceGridVisible()) {
         SurfaceInterpolator currentSurface = (SurfaceInterpolator) display_.getCurrentEditableSurfaceOrGrid();
         if (SwingUtilities.isRightMouseButton(e) && !mouseDragging_) {
            double z = display_.getZCoordinateOfDisplayedSlice();
            if (e.isShiftDown()) {
               //delete all points at slice
               currentSurface.deletePointsWithinZRange(Math.min(z - display_.getZStep() / 2, z + display_.getZStep() / 2),
                       Math.max(z - display_.getZStep() / 2, z + display_.getZStep() / 2));
            } else {
               //delete point if one is nearby
               Point2D.Double stagePos = display_.stageCoordFromImageCoords(e.getPoint().x, e.getPoint().y);
               //calculate tolerance
               Point2D.Double toleranceStagePos = display_.stageCoordFromImageCoords(e.getPoint().x + DELETE_SURF_POINT_PIXEL_TOLERANCE, e.getPoint().y + DELETE_SURF_POINT_PIXEL_TOLERANCE);
               double stageDistanceTolerance = Math.sqrt((toleranceStagePos.x - stagePos.x) * (toleranceStagePos.x - stagePos.x)
                       + (toleranceStagePos.y - stagePos.y) * (toleranceStagePos.y - stagePos.y));
               currentSurface.deleteClosestPoint(stagePos.x, stagePos.y, stageDistanceTolerance,
                       Math.min(z - display_.getZStep() / 2, z + display_.getZStep() / 2),
                       Math.max(z - display_.getZStep() / 2, z + display_.getZStep() / 2));
            }
         } else if (SwingUtilities.isLeftMouseButton(e)) {
            //convert to real coordinates in 3D space
            //Click point --> full res pixel point --> stage coordinate
            Point2D.Double stagePos = display_.stageCoordFromImageCoords(e.getPoint().x, e.getPoint().y);
            double z = display_.getZCoordinateOfDisplayedSlice();
            if (currentSurface == null) {
               Log.log("Can't add point--No surface selected", true);
            } else {
               currentSurface.addPoint(stagePos.x, stagePos.y, z);
            }
         }
      }
      if (mouseDragging_ && SwingUtilities.isRightMouseButton(e)) {
         //drag event finished, make sure pixels updated
         display_.recomputeDisplayedImage();
      }
      mouseDragging_ = false;
   }

   private void mouseDraggedActions(MouseEvent e) {
      Point currentPoint = e.getPoint();
      mouseDragging_ = true;
      if (SwingUtilities.isRightMouseButton(e)) {
         //pan
         display_.pan(mouseDragStartPointRight_.x - currentPoint.x, mouseDragStartPointRight_.y - currentPoint.y);
         mouseDragStartPointRight_ = currentPoint;
      } else if (SwingUtilities.isLeftMouseButton(e)) {
         //only move grid
         if (mode_ == SURFACE_AND_GRID && display_.getCurrentEditableSurfaceOrGrid() != null
                 && display_.getCurrentEditableSurfaceOrGrid() instanceof MultiPosGrid
                 && display_.isCurrentlyEditableSurfaceGridVisible()) {
            MultiPosGrid currentGrid = (MultiPosGrid) display_.getCurrentEditableSurfaceOrGrid();
            int dx = (currentPoint.x - mouseDragStartPointLeft_.x);
            int dy = (currentPoint.y - mouseDragStartPointLeft_.y);
            //convert pixel dx dy to stage dx dy
            Point2D.Double p0 = display_.stageCoordFromImageCoords(0, 0);
            Point2D.Double p1 = display_.stageCoordFromImageCoords(dx, dy);
            currentGrid.translate(p1.x - p0.x, p1.y - p0.y);
            mouseDragStartPointLeft_ = currentPoint;
         } else if (mode_ == EXPLORE) {
            display_.redrawOverlay();
         }
      }
   }

}
