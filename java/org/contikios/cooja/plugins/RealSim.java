package org.contikios.cooja.plugins;


import java.util.ArrayList;
import java.util.Observable;
import java.util.Observer;


import org.apache.log4j.Logger;

import org.contikios.cooja.Cooja;
import org.contikios.cooja.Mote;
import org.contikios.cooja.MoteType;
import org.contikios.cooja.Simulation;
import org.contikios.cooja.interfaces.Radio;
import org.contikios.cooja.radiomediums.AbstractRadioMedium;
import org.contikios.cooja.radiomediums.DGRMDestinationRadio;
import org.contikios.cooja.radiomediums.DirectedGraphMedium;
import org.contikios.cooja.radiomediums.DirectedGraphMedium.Edge;

public class RealSim implements Observer  {
	private static Logger	logger			= Logger.getLogger(RealSim.class);
	Simulation sim;
	Cooja cooja;
	private ArrayList<RealSimEdge> delayedEdges = new ArrayList<RealSimEdge>();
  
	
	
	RealSim(Simulation sim, Cooja cooja){
		this.sim = sim;
		this.cooja = cooja;
	}
	
	
	private String idOut(int id){
		Integer i = new Integer(id);
		return i.toString() + " (" + Integer.toHexString(id) + ")";
	}
	
	
	
	public void clear(){
		for(Mote m : sim.getMotes()) {
			rmMote(m.getID());
		}
	}
	
	public boolean moteExists(int id){
    int rid = sim.getRandomizedMoteId(id);
		if (sim.getMoteWithID(rid) != null) return true;
		if (sim.getMoteWithIDUninit(rid) != null) return true;
		return false;
	}
	
	
	
	
	public boolean addmote(Integer id, MoteType mt){
		
		if (moteExists(id)) {
			logger.info("Mote " + id + " already exists.");
			return false;
		}
		logger.info("Adding mote: " + id);
		
		Mote mote = mt.generateMote(sim);
		
		
		//Position at random place for a start
		// TODO use positioner?
		double x = 100; //(Math.random() * 10000) % 15;
		double y = 100; //(Math.random() * 10000) % 15;
		mote.getInterfaces().getPosition().setCoordinates(x, y, 0);
		mote.getInterfaces().getMoteID().setMoteID(id);
		
		
		//Add after everything is configured
		sim.addMote(mote);
		
		return true;
	}
	
	public boolean rmMote(Integer rid){
		Mote rmm  = sim.getMoteWithID(rid);
		DirectedGraphMedium rm = (DirectedGraphMedium) sim.getRadioMedium();
		
		if (rmm == null) {
			return false;
		}
		//remove edges
		for (DirectedGraphMedium.Edge e : rm.getEdges()) {
			Radio src = e.source;
			Radio dst = e.superDest.radio;
			if (src.getMote().getID() == rid || dst.getMote().getID() == rid) {
				rm.removeEdge(e);
			}
		}
		
		//remove mote
		sim.removeMote(rmm);
		return true;
	}
	
	
	private Edge rsEdge2Edge(RealSimEdge rse){
		DirectedGraphMedium rm = (DirectedGraphMedium) sim.getRadioMedium();
	
		for (DirectedGraphMedium.Edge e : rm.getEdges()) {
			int src = e.source.getMote().getID();
			int dst = e.superDest.radio.getMote().getID();
      int channel = e.superDest.getChannel();
			if (src == sim.getRandomizedMoteId(rse.src) && dst == sim.getRandomizedMoteId(rse.dst) && channel == rse.channel) {
				return e;
			}
		}
		return null;
	}
	
  public boolean rmEdge(int src, int dst, int channel){
    return rmEdge(new RealSimEdge(src, dst, channel));
	}
	
	public boolean rmEdge(RealSimEdge rse){
		DirectedGraphMedium rm = (DirectedGraphMedium) sim.getRadioMedium();
		
		Edge ed = rsEdge2Edge(rse);
		if(ed == null) return false;
		
		rm.removeEdge(ed);
		return true;
	}
	
  public RealSimEdge setEdge(int src, int dst, int channel, double ratio, double rssi, int lqi) {
    RealSimEdge rse = new RealSimEdge(src, dst, channel);
		rse.ratio = ratio;
		rse.rssi = rssi;
		rse.lqi = lqi;
		setEdge(rse);
		return rse;
	}
	
	
	public boolean setEdge(RealSimEdge rse){
		// Remove old existing edge
		
		
//		logger.info("Setting edge: " + idOut(rse.src) + " - " + idOut(rse.dst) + " PRR: " + rse.ratio + " RSSI: " + rse.rssi + " LQI: " + rse.lqi + " channel: " + rse.channel);
		DirectedGraphMedium rm = (DirectedGraphMedium) sim.getRadioMedium();
		DGRMDestinationRadio dr;

    int rsrc = sim.getRandomizedMoteId(rse.src);
    int rdst = sim.getRandomizedMoteId(rse.dst);
		
		//Check whether the nodes are initialized
		if(sim.getMoteWithID(rsrc) == null || sim.getMoteWithID(rdst) == null){
			if(!moteExists(rsrc)){
				logger.error("Mote " + rsrc + "/" + rse.src + " does not exist");
				return false;
			}
			if(!moteExists(rdst)){
				logger.error("Mote " + rdst + "/" + rse.dst + " does not exist");
				return false;
			}
			delayedEdges.add(rse);
			return true;
		}
		
		
		Edge edge = rsEdge2Edge(rse);
		
		if(edge != null){
			dr = (DGRMDestinationRadio)edge.superDest;
		} else {
			dr = new DGRMDestinationRadio(sim.getMoteWithID(rdst).getInterfaces().getRadio());
      dr.channel = rse.channel;
		}
		
		dr.ratio = rse.ratio;
		dr.signal = (rse.rssi);
		dr.lqi = rse.lqi;
		
		if(edge == null){
			edge = new Edge(sim.getMoteWithID(rsrc).getInterfaces().getRadio(), dr);
			rm.addEdge(edge);
		}
		
		//TODO: Set delay
		
		rm.requestEdgeAnalysis(); //This just sets a flag as done by addEdge - No harm in doing it again
		return true;
		
	}

	public boolean setBaseRssi(int moteid, double baserssi){
		AbstractRadioMedium dgm = (AbstractRadioMedium) sim.getRadioMedium();
    int rmoteid = sim.getRandomizedMoteId(moteid);
		Mote mote = sim.getMoteWithID(rmoteid);
		
		if (mote == null) return false;

		dgm.setBaseRssi(mote.getInterfaces().getRadio(), baserssi);

		return true;
	}
	
	@Override
	public void update(Observable o, Object arg) {
		 if (!(arg instanceof Mote)) {
          return;
		 }
		 if(delayedEdges.size() == 0) return;
		 
		 ArrayList<RealSimEdge> old = delayedEdges;
		 
		 //Create new empty list, as the edges will be enqueued by setEdge
		 delayedEdges = new ArrayList<RealSimEdge>(old.size());
		 
		 for(RealSimEdge rse : old){
			 setEdge(rse);
		 }
		 
	}
	
}
