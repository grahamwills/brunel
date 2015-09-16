/*
 * Copyright (c) 2015 IBM Corporation and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.brunel.app;

import java.io.IOException;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.brunel.action.Action;
import org.brunel.build.Builder;
import org.brunel.build.controls.ControlWriter;
import org.brunel.build.controls.Controls;
import org.brunel.build.d3.D3Builder;
import org.brunel.build.util.ContentReader;
import org.brunel.build.util.DataCache;
import org.brunel.data.Dataset;
import org.brunel.data.io.CSV;
import org.brunel.match.BestMatch;
import org.brunel.model.VisItem;
import org.brunel.util.WebDisplay;

/**
 * Sample JAX-RS web application that produces Brunel visualizations.  Currently only d3 output is supported.
 * Service methods are provided to create the raw Brunel content (JS/CSS) or a full HTML page.  An additional service
 * method can generate Brunel that shows a given visualization on new data.
 * <p/>
 * REST pattern for raw content is:
 * <p/>
 * POST /brunel/interpret/d3?src={brunel}&width=..&height=..
 * <p/>
 * Payload is expected to be CSV as TEXT/PLAIN.
 */

@ApplicationPath("brunel")
@Path("interpret")
public class BrunelService extends Application {

    /**
     * Generates all JS/CSS using D3 to produce a visualization.  The data can be on the payload or it can be specified using
     * the Brunel data() function.
     * @param data   the data to use for the visualization (as URL or cache identifier)
     * @param brunelSrc the Brunel syntax defining the visualization
     * @param width the desired width of the resulting visualization
     * @param height the desired height of the resulting visualization
     * @param visId an identifier to use for the d3 JS to reference the HTML tag containing the visualization on the web page (usually an SVG tag).  
     * @return a JSON object containing the css, js, and an object describing interactive controls that require a separate UI
     */
    @POST
    @Path("d3")							   
    @Consumes(MediaType.TEXT_PLAIN)        //A CSV file is the payload
    @Produces(MediaType.APPLICATION_JSON)  //JSON object with "js" and "css" entries
    public D3Result createAsD3(String data, @QueryParam("src") String brunelSrc,
                               @QueryParam("width") int width,
                               @QueryParam("height") int height,
                               @QueryParam("visid") String visId) {
        Builder builder = makeD3(makeBrunelData(data), brunelSrc, width, height, visId);
        D3Result result = new D3Result();
        result.css = builder.getStyleOverrides();
        result.js = builder.getVisualization().toString();
        result.controls = builder.getControls();
        return result;
    }

    /**
     * Creates a full HTML page suitable for use within an HTML IFrame.
     * @param brunelSrc the Brunel syntax defining the visualization
     * @param brunelUrl (optional) a URL to a file containing the Brunel syntax
     * @param width the desired width of the resulting visualization
     * @param height the desired height of the resulting visualization
     * @param dataUrl a URL pointing to the CSV to use for the visualization's data.  Note if the Brunel contains a data()
     *  function, then this will be used instead
     * @param filesLoc (optional) an alternate location for the main Brunel javascript
     * @return a full HTML page with all JS/CSS and interactive controls for a given visualization.
     */
    @GET
    @Path("d3")
    @Produces(MediaType.TEXT_HTML)
    public String createAsD3Html(@QueryParam("brunel_src") String brunelSrc,
    							 @QueryParam("brunel_url") String brunelUrl,
                                 @QueryParam("width") int width,
                                 @QueryParam("height") int height,
                                 @QueryParam("data") String dataUrl,
                                 @QueryParam("files") String filesLoc
    ) {

    	try {
	    	String src = brunelSrc != null ? brunelSrc : ContentReader.readContentFromUrl(brunelUrl);

	        Builder builder = makeD3(readBrunelData(dataUrl), src, width, height, "visualization");
	        String css = builder.getStyleOverrides();
	        String js = (String) builder.getVisualization();
	        Controls controls = builder.getControls();
	        js += new ControlWriter("controls", "BrunelJQueryControlFactory").write(controls);
	        if (filesLoc == null) filesLoc = "../../brunelsupport";
	        if (width < 5) width = 800;
	        if (height < 5) height = 600;
	        return WebDisplay.writeHtml(css, js, width, height, filesLoc, null, controls, "");
    	}
    	catch (IOException ex) {
    		 throw makeException("Could not read brunel from: " + brunelUrl, Status.BAD_REQUEST.getStatusCode());
    	}

    }

    
	/**
	 * Service that creates new Brunel syntax to use a given visualization with new data.
	 * @param originalData the original data (as URL or cache identifier)
	 * @param newData the new data (as URL or cache identifier)
	 * @param brunelSrc the Brunel syntax that produced the original visualization
	 * @return Brunel syntax using the new data 
	 */
    @GET
    @Path("match")
    @Produces(MediaType.TEXT_PLAIN)

    public String actionFromExisting(@QueryParam("original_data") String originalData,
                                     @QueryParam("new_data") String newData,
                                     @QueryParam("src") String brunelSrc) {
        try {
        	if (originalData != null) {
	            Dataset origDS = DataCache.get(originalData);
	            Dataset newDS = DataCache.get(newData);
	            return BestMatch.match(origDS, newDS, Action.parse(brunelSrc)).toString();
        	}
        	else {
        		return BestMatch.match(brunelSrc, newData).toString();
        	}
        } catch (IOException e) {
            // You would have to be really unlucky to get this -- the cache would have to be flushed and then the
            // the remote fle fail to be read.
            throw makeException("Could not read data for match: " + e.getMessage(), Status.BAD_REQUEST.getStatusCode());
        }
    }


    //Creates a D3Builder to produce the d3 output
    private Builder makeD3(Dataset data, String actionText, int width, int height, String visId) {

        try {
            D3Builder builder = new D3Builder(visId);
            VisItem item = makeVisItem(data, actionText);
            builder.build(item, width, height);
            return builder;
        } catch (Exception ex) {
        	ex.printStackTrace();
            throw makeException("Could not execute Brunel: " + actionText + ": " + ex.getMessage(), Status.BAD_REQUEST.getStatusCode());
        }
    }

    //Create a Dataset instance given CSV
    private Dataset makeBrunelData(String data) {
        try {
            return  Dataset.make(CSV.read(data));
        } catch (Exception e) {
            throw makeException("Could not create data as CSV from content", Status.BAD_REQUEST.getStatusCode());
        }
    }


    //Get a Dataset instance given a URL.  The content will be loaded if not present in the cache.
    private Dataset readBrunelData(String url) {
        try {
            return DataCache.get(url);
        } catch (Exception e) {
            throw makeException("Could not read data as CSV from: " + url, Status.BAD_REQUEST.getStatusCode());
        }
    }

    //Create the VisItem instance for the given Brunel
    private VisItem makeVisItem(Dataset brunel, String actionText) {
        Action action = Action.parse(actionText);
        return action.apply(brunel);
    }

    //Simple web exception handling
    private WebApplicationException makeException(String message, int code) {

        return new WebApplicationException(
                Response.status(Status.fromStatusCode(code))
                        .entity(message).type(MediaType.TEXT_PLAIN).build());
    }


}
