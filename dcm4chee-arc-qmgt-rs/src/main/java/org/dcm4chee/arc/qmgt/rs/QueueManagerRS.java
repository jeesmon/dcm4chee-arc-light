/*
 * *** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2017
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * *** END LICENSE BLOCK *****
 */

package org.dcm4chee.arc.qmgt.rs;

import com.querydsl.core.types.Predicate;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.entity.QueueMessage;
import org.dcm4chee.arc.event.BulkQueueMessageEvent;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.event.QueueMessageOperation;
import org.dcm4chee.arc.qmgt.IllegalTaskStateException;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.query.util.MatchTask;
import org.dcm4chee.arc.rs.client.RSClient;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.RequestScoped;
import javax.enterprise.event.Event;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.Pattern;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Date;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Sep 2015
 */
@RequestScoped
@Path("queue/{queueName}")
public class QueueManagerRS {
    private static final Logger LOG = LoggerFactory.getLogger(QueueManagerRS.class);

    @Inject
    private QueueManager mgr;

    @Inject
    private Device device;

    @Inject
    private RSClient rsClient;

    @Inject
    private Event<QueueMessageEvent> queueMsgEvent;

    @Inject
    private Event<BulkQueueMessageEvent> bulkQueueMsgEvent;

    @Context
    private HttpServletRequest request;

    @PathParam("queueName")
    private String queueName;

    @QueryParam("dicomDeviceName")
    private String deviceName;

    @QueryParam("status")
    @Pattern(regexp = "SCHEDULED|IN PROCESS|COMPLETED|WARNING|FAILED|CANCELED")
    private String status;

    @QueryParam("offset")
    @Pattern(regexp = "0|([1-9]\\d{0,4})")
    private String offset;

    @QueryParam("limit")
    @Pattern(regexp = "[1-9]\\d{0,4}")
    private String limit;

    @QueryParam("createdTime")
    private String createdTime;

    @QueryParam("updatedTime")
    private String updatedTime;

    @QueryParam("batchID")
    private String batchID;

    @QueryParam("JMSMessageID")
    private String jmsMessageID;

    @QueryParam("orderby")
    @DefaultValue("-updatedTime")
    @Pattern(regexp = "(-?)createdTime|(-?)updatedTime")
    private String orderby;

    @GET
    @NoCache
    @Produces("application/json")
    public Response search() {
        logRequest();
        return Response.ok(toEntity(mgr.search(
                MatchTask.matchQueueMessage(queueName, deviceName, status(), batchID, jmsMessageID, createdTime, updatedTime, null),
                MatchTask.queueMessageOrder(orderby), parseInt(offset), parseInt(limit))))
                .build();
    }

    @GET
    @NoCache
    @Path("/count")
    @Produces("application/json")
    public Response countTasks() {
        logRequest();
        return count(mgr.countTasks(MatchTask.matchQueueMessage(
                        queueName, deviceName, status(), batchID, jmsMessageID, createdTime, updatedTime, null)));
    }

    @POST
    @Path("{msgId}/cancel")
    public Response cancelProcessing(@PathParam("msgId") String msgId) {
        logRequest();
        QueueMessageEvent queueEvent = new QueueMessageEvent(request, QueueMessageOperation.CancelTasks);
        try {
            return rsp(mgr.cancelTask(msgId, queueEvent));
        } catch (IllegalTaskStateException e) {
            queueEvent.setException(e);
            return rsp(Response.Status.CONFLICT, e.getMessage());
        } finally {
            queueMsgEvent.fire(queueEvent);
        }
    }

    @POST
    @Path("/cancel")
    public Response cancelTasks() {
        logRequest();
        QueueMessage.Status status = status();
        if (status == null)
            return rsp(Response.Status.BAD_REQUEST, "Missing query parameter: status");
        if (status != QueueMessage.Status.SCHEDULED && status != QueueMessage.Status.IN_PROCESS)
            return rsp(Response.Status.BAD_REQUEST, "Cannot cancel tasks with status: " + status);

        BulkQueueMessageEvent queueEvent = new BulkQueueMessageEvent(request, QueueMessageOperation.CancelTasks);
        try {
            LOG.info("Cancel processing of Tasks with Status {} at Queue {}", this.status, queueName);
            Predicate matchQueueMessage = MatchTask.matchQueueMessage(queueName, deviceName, status, batchID, jmsMessageID,
                    createdTime, updatedTime, null);
            long count = mgr.cancelTasks(matchQueueMessage, status);
            queueEvent.setCount(count);
            return count(count);
        } catch (IllegalTaskStateException e) {
            queueEvent.setException(e);
            return rsp(Response.Status.CONFLICT, e.getMessage());
        } finally {
            bulkQueueMsgEvent.fire(queueEvent);
        }
    }

    @POST
    @Path("{msgId}/reschedule")
    public Response rescheduleMessage(@PathParam("msgId") String msgId) throws Exception {
        logRequest();
        QueueMessageEvent queueEvent = new QueueMessageEvent(request, QueueMessageOperation.RescheduleTasks);
        try {
            String devName = mgr.rescheduleTask(msgId, null, queueEvent);
            return devName == null
                    ? Response.status(Response.Status.NOT_FOUND).build()
                    : devName.equals(device.getDeviceName())
                        ? Response.status(Response.Status.NO_CONTENT).build()
                        : rsClient.forward(request, devName);
        } catch (IllegalTaskStateException e) {
            queueEvent.setException(e);
            return rsp(Response.Status.CONFLICT, e.getMessage());
        } finally {
            queueMsgEvent.fire(queueEvent);
        }
    }

    @POST
    @Path("/reschedule")
    public Response rescheduleMessages() throws Exception {
        logRequest();
        QueueMessage.Status status = status();
        if (status == null)
            return rsp(Response.Status.BAD_REQUEST, "Missing query parameter: status");
        if (status == QueueMessage.Status.SCHEDULED || status == QueueMessage.Status.IN_PROCESS)
            return rsp(Response.Status.BAD_REQUEST, "Cannot reschedule tasks with status: " + status);

        Predicate matchQueueMessage = MatchTask.matchQueueMessage(
                queueName, deviceName, status, batchID, jmsMessageID, createdTime, updatedTime, new Date());

        if (deviceName == null) {
            List<String> distinctDeviceNames = mgr.listDistinctDeviceNames(matchQueueMessage);
            for (String devName : distinctDeviceNames) {
                Response response = devName.equals(device.getDeviceName())
                        ? rescheduleMessages(matchQueueMessage)
                        : rsClient.forward(request, devName);
                LOG.info("Tasks rescheduled on device: {}. Response received with status: {} and entity: {}",
                        devName, response.getStatus(), response.getEntity().toString());
            }
            return Response.ok().build();
        }
        return !deviceName.equals(device.getDeviceName())
                ? rsClient.forward(request, deviceName)
                : rescheduleMessages(matchQueueMessage);
    }

    private Response rescheduleMessages(Predicate matchQueueMessage) {
        BulkQueueMessageEvent queueEvent = new BulkQueueMessageEvent(request, QueueMessageOperation.RescheduleTasks);
        try {
            ArchiveDeviceExtension arcDev = device.getDeviceExtension(ArchiveDeviceExtension.class);
            int fetchSize = arcDev.getQueueTasksFetchSize();
            int count = 0;
            List<String> queueMsgIDs;
            do {
                queueMsgIDs = mgr.getQueueMsgIDs(matchQueueMessage, fetchSize);
                for (String msgID : queueMsgIDs)
                    mgr.rescheduleTask(msgID, queueName, null);
                count += queueMsgIDs.size();
            } while (queueMsgIDs.size() >= fetchSize);
            queueEvent.setCount(count);
            return count(count);
        } catch (IllegalTaskStateException e) {
            queueEvent.setException(e);
            return rsp(Response.Status.CONFLICT, e.getMessage());
        } finally {
            bulkQueueMsgEvent.fire(queueEvent);
        }
    }

    @DELETE
    @Path("{msgId}")
    public Response deleteMessage(@PathParam("msgId") String msgId) {
        logRequest();
        QueueMessageEvent queueEvent = new QueueMessageEvent(request, QueueMessageOperation.DeleteTasks);
        boolean deleteTask = mgr.deleteTask(msgId, queueEvent);
        queueMsgEvent.fire(queueEvent);
        return rsp(deleteTask);
    }

    @DELETE
    @Produces("application/json")
    public String deleteMessages() {
        logRequest();
        BulkQueueMessageEvent queueEvent = new BulkQueueMessageEvent(request, QueueMessageOperation.DeleteTasks);
        int deleted = mgr.deleteTasks(queueName, MatchTask.matchQueueMessage(
                queueName, deviceName, status(), batchID, jmsMessageID, createdTime, updatedTime, null));
        queueEvent.setCount(deleted);
        bulkQueueMsgEvent.fire(queueEvent);
        return "{\"deleted\":" + deleted + '}';
    }

    private static Response rsp(Response.Status status, Object entity) {
        return Response.status(status).entity(entity).build();
    }

    private static Response rsp(boolean result) {
        return Response.status(result
                ? Response.Status.NO_CONTENT
                : Response.Status.NOT_FOUND)
                .build();
    }

    private static Response count(long count) {
        return rsp(Response.Status.OK, "{\"count\":" + count + '}');
    }

    private StreamingOutput toEntity(final List<QueueMessage> msgs) {
        return out -> {
                Writer w = new OutputStreamWriter(out, "UTF-8");
                int count = 0;
                w.write('[');
                for (QueueMessage msg : msgs) {
                    if (count++ > 0)
                        w.write(',');
                    msg.writeAsJSON(w);
                }
                w.write(']');
                w.flush();
        };
    }

    private QueueMessage.Status status() {
        return status != null ? QueueMessage.Status.fromString(status) : null;
    }

    private static int parseInt(String s) {
        return s != null ? Integer.parseInt(s) : 0;
    }

    private void logRequest() {
        LOG.info("Process {} {} from {}@{}", request.getMethod(), request.getRequestURI(),
                request.getRemoteUser(), request.getRemoteHost());
    }
}
