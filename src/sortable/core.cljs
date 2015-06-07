(ns ^:figwheel-always sortable.core
    (:require-macros [cljs.core.async.macros :refer [go go-loop]])
    (:require [cljs.core.async :as async :refer [>! <! chan]]
              [om.core :as om :include-macros true]
              [om.dom :as dom :include-macros true]
              [goog.style :as style]
              [jamesmacaulay.zelkova.signal :as z]
              [jamesmacaulay.zelkova.mouse :as mouse]))

(enable-console-print!)

(defn ensure-attrs [k f]
  (fn [box]
    (if (nil? (k box))
      (f box)
      box)))

(defn add-node [box]
  {:pre [(some? (::id box))]}
  (assoc box :node (.getElementById js/document (::id box))))

(def ensure-node (ensure-attrs :node add-node))

(defn add-size [box]
  (let [box' (ensure-node box)
        n (aget (.-childNodes (:node box')) 0) 
        size (style/getSize n)]
    (assoc box' :width (.-width size) :height (.-height size))))

(def ensure-size (ensure-attrs :width add-size))

(defn add-pos [box]
  (let [box' (ensure-node box)
        final-pos (style/getPageOffset (:node box'))
        left (.-x final-pos)
        top (.-y final-pos)]
    (assoc box' :left left :top top)))

(def ensure-pos (ensure-attrs :left add-pos))

(defn box-center [box]
  (let [box' (ensure-pos (ensure-size box))]
    (letfn [(add [kb ks]
              (+ (kb box') (/ (ks box') 2)))]
      [(add :left :width) (add :top :height)])))

(defn filler-box? [box]
  (= "filler-box" (::id box)))

(defn filler-box []
  {::id "filler-box"})

(defn in-box? [[x y] box]
  (let [{:keys [top left width height]} (ensure-pos (ensure-size box))]
    (and (< left x (+ left width))
      (< top y (+ top height)))))

(defn moving?
  [box]
  (contains? box :drag-offset))

(defn topleft-pos
  [{:keys [left top]}]
  [left top])

(defn start-dragging-box-from-pos
  [pos box]
  (let [offset (->> box topleft-pos (map - pos))]
    (assoc box :drag-offset offset)))

(defn start-drag
  "Identifies the boxes to be dragged (or build) and returns the updated state"
  [pos state]
  (let [drag-target? (partial in-box? pos)]
    (-> state
      (update-in [:boxes]
        (fn [boxes]
          (->> (map (comp add-size add-pos add-node) boxes)
            (mapv #(if (drag-target? %)
                     (start-dragging-box-from-pos pos %)
                     %)))))
      (assoc :drag {:start-pos pos}))))

(defn sort-by-pos [boxes]
  (vec (sort-by (comp second box-center add-size add-pos add-node) boxes)))

(defn drag
  "Updates the state by interpreting what the new position means for each box."
  [pos state]
  (letfn [(drag-to-pos [box]
            (let [[left top] (map - pos (:drag-offset box))]
              (assoc box :left left :top top)))]
    (update-in state [:boxes]
      (fn [boxes]
        (->> boxes 
          (map (comp add-pos add-node))
          (map #(if (moving? %) (drag-to-pos %) %))
          sort-by-pos)))))

(defn drop-boxes
  [state]
  (letfn [(drop-box [box]
            (dissoc box :drag-offset))]
    (update-in state [:boxes] (partial mapv drop-box))))

(defn stop-drag
  [state]
  (-> state
    drop-boxes
    (assoc :drag nil)))

(defrecord NoOp [] IFn (-invoke [_ state] state))
(defrecord StartDrag [pos] IFn (-invoke [_ state] (start-drag pos state)))
(defrecord Drag [pos] IFn (-invoke [_ state] (drag pos state)))
(defrecord StopDrag [ch]
  IFn
  (-invoke [_ state]
    (let [state' (stop-drag state)]
      (go (>! ch [::stop state']))
      state')))

(defn state-signal [stop-ch init-state]
  (let [dragging-positions (z/keep-when mouse/down?
                             [0 0]
                             mouse/position)
        dragging? (->> (z/constant true)
                    (z/sample-on dragging-positions)
                    (z/merge (z/keep-if not false mouse/down?))
                    (z/drop-repeats))
        dragstarts (z/keep-if identity true dragging?)
        dragstops (z/keep-if not false dragging?)
        actions (z/merge (z/constant (->NoOp))
                  (z/map ->StartDrag (z/sample-on dragstarts mouse/position))
                  (z/map (constantly (->StopDrag stop-ch)) dragstops)
                  (z/map ->Drag dragging-positions))]
    (z/foldp (fn [action state]
               (assoc (action state)
                 :last-action (pr-str action)))
      init-state
      actions)))

(defn sort-draggable [[box item] owner opts]
  (reify
    om/IDisplayName (display-name [_] "SortDraggable")
    om/IRender
    (render [_]
      (dom/div #js {:id (::id box)
                    :class "sortable-draggable"
                    :style #js {:position "absolute"
                                :top (:top box)
                                :left (:left box)}}
        (om/build (:box-view opts) item)))))

(defn sort-filler [[box item] owner opts]
  (reify
    om/IDisplayName (display-name [_] "SortFiller")
    om/IRender
    (render [_]
      (dom/div #js {:id "filler-box"
                    :class "sortable-filler"
                    :style #js {:position "relative"}}
        (om/build (:box-filler opts) item)))))

(defn sort-wrapper [[box item] owner opts]
  (reify
    om/IDisplayName (display-name [_] "SortWrapper")
    om/IRender
    (render [_]
      (dom/div #js {:id (::id box)
                    :class "sortable-container"
                    :style #js {:position "relative"}}
        (om/build (:box-view opts) item)))))

(defn find-by-key [k v coll]
  (first (filter #(= v (get % k)) coll)))

(defn sortable [items owner opts]
  (reify
    om/IDisplayName (display-name [_] "Sortable")
    om/IInitState
    (init-state [_]
      (let [boxes (mapv #(hash-map ::id (gensym)
                           ::key (get % (:id-key opts)))
                    items)]
        {:boxes boxes 
         :stop-ch (chan)
         :drag nil}))
    om/IWillMount
    (will-mount [_]
      (go-loop []
        (let [[tag state] (<! (om/get-state owner :stop-ch))]
          (case tag
            ::stop 
            (om/transact! items
              #(mapv (fn [box]
                       (find-by-key (:id-key opts) (::key box) %))
                 (:boxes state)))))
        (recur)))
    om/IDidMount
    (did-mount [_]
      (let [boxes (mapv (comp add-pos add-node)
                    (om/get-state owner :boxes))
            state-ref (-> (om/get-state owner :stop-ch)
                        (state-signal {:boxes boxes})
                        z/pipe-to-atom )]
        (add-watch state-ref ::sortable
          (fn [_ _ _ nv]
            (om/update-state! owner #(merge % nv))))
        (om/set-state! owner :state-ref state-ref)
        (om/set-state! owner :boxes boxes)))
    om/IWillUnmount
    (will-unmount [_]
      (remove-watch (om/get-state owner :state-ref) ::sortable))
    om/IRenderState
    (render-state [_ {:keys [boxes]}]
      (apply dom/div {:class "sort-list"} 
        (dom/pre nil (.stringify js/JSON (clj->js boxes) nil 2))
        (if-let [draggable (first (filter moving? boxes))]
          (om/build sort-draggable
            [draggable (find-by-key (:id-key opts) (::key draggable) items)]
            {:opts opts}))
        (->> (map ensure-node boxes)
          (map-indexed vector)
          (sort-by 
            (fn [[index box]]
              (if (nil? (:node box))
                index
                (second (box-center box)))))
          (map (fn [[_ box]]
                 (let [item (find-by-key (:id-key opts) (::key box) items)]
                   (if (moving? box)
                     (om/build sort-filler [box item] {:opts opts})
                     (om/build sort-wrapper [box item] {:opts opts}))))))))))

;; Example

(defn pos->hue
  [[x y]]
  (mod (+ (/ x 2) (/ y 2)) 360))

(def box-width 50)
(def box-height 20)

(defn build-box
  [id]
  {:item-id id 
   :width box-width
   :height (+ box-height (* 10 id)) 
   :hue (pos->hue [(rand-int 500) (rand-int 500)])})

(defonce app-state (atom {:boxes (mapv build-box (range 5))}))

(defn box-color
  [box]
  (let [opacity (if (moving? box) 0.5 1)]
    (str "hsla(" (:hue box) ",50%,50%," opacity ")")))

(defn render-filler [box owner]
  (reify
    om/IDisplayName (display-name [_] "Filler")
    om/IRender
    (render [_]
      (dom/div nil 
        "Filler Box"))))

(defn render-item [item owner]
  (reify
    om/IDisplayName (display-name [_] "Box")
    om/IRender
    (render [_]
      (when item 
        (dom/div #js {:style #js {:backgroundColor (box-color item)
                                  :width (:width item)
                                  :height (:height item)}}
          (:item-id item))))))

(defn render-state
  [state]
  (dom/div #js {:style #js {:-webkit-touch-callout "none"
                            :-webkit-user-select "none"
                            :-khtml-user-select "none"
                            :-moz-user-select "none"
                            :-ms-user-select "none"
                            :user-select "none"}}
    (dom/div #js {:style #js {:position "relative"}}
      (dom/h1 nil "Drag and drop")
      (dom/p nil (pr-str (map :item-id (:boxes state))))
      (om/build sortable (:boxes state)
        {:opts {:box-view render-item
                :id-key :item-id
                :box-filler render-filler}}))))

(om/root
  (fn [app owner]
    (reify om/IRender
      (render [_]
        (render-state app))))
  app-state
  {:target (. js/document (getElementById "app"))})

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
) 
