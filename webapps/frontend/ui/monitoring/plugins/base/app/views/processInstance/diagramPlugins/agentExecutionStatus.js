'use strict';

var angular = require('angular');

// Badge on a BPMN element backed by an Agent Execution (see agent-webhook-plugin):
// polls GET /agent-webhook/executions?processInstanceId=... and shows
// Running/Waiting/Completed/Failed + elapsed time + (while waiting) the tool/args
// that triggered the wait. Text is updated in place on each tick/poll rather than
// tearing the overlay down each time, so it reads as a live update, not a flicker.

var STATE_LABELS = {
  CREATED: 'RUNNING',
  STARTING: 'RUNNING',
  RUNNING: 'RUNNING',
  WAITING: 'WAITING',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED',
  CANCELLED: 'CANCELLED'
};

var STATE_COLORS = {
  CREATED: '#2f6fb3',
  STARTING: '#2f6fb3',
  RUNNING: '#2f6fb3',
  WAITING: '#c2760a',
  COMPLETED: '#3a8f2f',
  FAILED: '#b3261e',
  CANCELLED: '#6b6b6b'
};

var POLL_INTERVAL_MS = 3000;
var TICK_INTERVAL_MS = 1000;
var REMOVE_TERMINAL_AFTER_MS = 6000;

function isTerminal(state) {
  return state === 'COMPLETED' || state === 'FAILED' || state === 'CANCELLED';
}

function formatElapsed(ms) {
  if (!isFinite(ms) || ms < 0) {
    ms = 0;
  }
  var totalSeconds = Math.floor(ms / 1000);
  var minutes = Math.floor(totalSeconds / 60);
  var seconds = totalSeconds % 60;
  return minutes + 'm ' + (seconds < 10 ? '0' : '') + seconds + 's';
}

function formatWaitingReason(execution) {
  if (!execution.waitingTool) {
    return '';
  }
  var argsLabel = '';
  if (execution.waitingArgs) {
    try {
      var parsed = JSON.parse(execution.waitingArgs);
      argsLabel = Object.keys(parsed)
        .map(function(key) {
          return key + '=' + parsed[key];
        })
        .join(', ');
    } catch (e) {
      argsLabel = execution.waitingArgs;
    }
  }
  return (
    'Waiting on ' + execution.waitingTool + (argsLabel ? ': ' + argsLabel : '')
  );
}

var BADGE_TEMPLATE =
  '<div class="agent-execution-status-badge" style="' +
  'font-family: sans-serif; font-size: 13px; font-weight: 700; line-height: 1; ' +
  'color: #fff; border-radius: 14px; padding: 6px 14px; white-space: nowrap; ' +
  'box-shadow: 0 2px 6px rgba(0,0,0,.3); letter-spacing: .03em;">' +
  '<span class="agent-status-label"></span>' +
  '<span class="agent-status-elapsed" style="font-weight: 400; opacity: .85;"></span>' +
  '<div class="agent-status-reason" style="font-weight: 400; font-size: 11px; ' +
  'margin-top: 3px; max-width: 240px; white-space: normal; opacity: .95;"></div>' +
  '</div>';

module.exports = [
  'ViewsProvider',
  function(ViewsProvider) {
    ViewsProvider.registerDefaultView(
      'monitoring.processInstance.diagram.plugin',
      {
        id: 'agent-execution-status-overlay',
        overlay: [
          '$scope',
          '$http',
          'control',
          'processData',
          function($scope, $http, control, processData) {
            var overlays = control.getViewer().get('overlays');
            var elementRegistry = control.getViewer().get('elementRegistry');

            var executionsByActivity = {};
            var badgesByActivity = {};
            var terminalSinceByActivity = {};
            var pollHandle = null;
            var tickHandle = null;
            var currentProcessInstanceId = null;

            function removeBadge(activityId) {
              var badge = badgesByActivity[activityId];
              if (badge) {
                overlays.remove(badge.overlayId);
                delete badgesByActivity[activityId];
              }
            }

            function reset() {
              if (pollHandle) {
                window.clearInterval(pollHandle);
                pollHandle = null;
              }
              if (tickHandle) {
                window.clearInterval(tickHandle);
                tickHandle = null;
              }
              Object.keys(badgesByActivity).forEach(removeBadge);
              executionsByActivity = {};
              terminalSinceByActivity = {};
            }

            function createBadge(activityId) {
              var container = angular.element(BADGE_TEMPLATE);
              var overlayId = overlays.add(activityId, {
                position: {top: -38, left: 0},
                show: {minZoom: -Infinity, maxZoom: +Infinity},
                html: container
              });
              var badge = {
                overlayId: overlayId,
                container: container,
                labelEl: container.find('.agent-status-label'),
                elapsedEl: container.find('.agent-status-elapsed'),
                reasonEl: container.find('.agent-status-reason')
              };
              badgesByActivity[activityId] = badge;
              return badge;
            }

            function updateBadge(badge, execution, elapsedLabel, reasonLabel) {
              var color = STATE_COLORS[execution.state] || '#6b6b6b';
              badge.container.css('background', color);
              badge.labelEl.text(
                STATE_LABELS[execution.state] || execution.state
              );
              badge.elapsedEl.text(' · ' + elapsedLabel);
              if (reasonLabel) {
                badge.reasonEl.text(reasonLabel).css('display', 'block');
              } else {
                badge.reasonEl.css('display', 'none');
              }
            }

            function render() {
              Object.keys(executionsByActivity).forEach(function(activityId) {
                var execution = executionsByActivity[activityId];
                var element = elementRegistry.get(activityId);
                if (!element) {
                  return;
                }

                var terminal = isTerminal(execution.state);
                if (terminal) {
                  if (!terminalSinceByActivity[activityId]) {
                    terminalSinceByActivity[activityId] = Date.now();
                  }
                  if (
                    Date.now() - terminalSinceByActivity[activityId] >
                    REMOVE_TERMINAL_AFTER_MS
                  ) {
                    removeBadge(activityId);
                    delete executionsByActivity[activityId];
                    delete terminalSinceByActivity[activityId];
                    return;
                  }
                } else {
                  terminalSinceByActivity[activityId] = null;
                }

                var startedAt = new Date(execution.createdAt).getTime();
                var endedAt = terminal
                  ? new Date(execution.updatedAt).getTime()
                  : Date.now();
                var reasonLabel =
                  execution.state === 'WAITING'
                    ? formatWaitingReason(execution)
                    : '';

                var badge =
                  badgesByActivity[activityId] || createBadge(activityId);
                updateBadge(
                  badge,
                  execution,
                  formatElapsed(endedAt - startedAt),
                  reasonLabel
                );
              });
            }

            function poll(processInstanceId) {
              $http
                // Deliberately NOT Uri.appUri('engine://...') - that scheme resolves to
                // /fluxnova/api/engine/, a different (proxied) REST mount than the one
                // AgentWebhookResource is actually registered on (/engine-rest/, verified
                // directly). Hitting the wrong mount silently 404s.
                .get('/engine-rest/agent-webhook/executions', {
                  params: {processInstanceId: processInstanceId}
                })
                .then(function(response) {
                  (response.data || []).forEach(function(execution) {
                    if (execution.activityId) {
                      executionsByActivity[execution.activityId] = execution;
                    }
                  });
                  render();
                });
            }

            processData.observe('processInstance', function(processInstance) {
              if (!processInstance || !processInstance.id) {
                return;
              }
              if (processInstance.id === currentProcessInstanceId) {
                return;
              }

              // A different instance than the one we were polling - either the first
              // load, or Monitoring reused this controller/scope across a navigation
              // between two process-instance pages (same view, only :id changed).
              reset();
              currentProcessInstanceId = processInstance.id;

              poll(currentProcessInstanceId);
              pollHandle = window.setInterval(function() {
                poll(currentProcessInstanceId);
              }, POLL_INTERVAL_MS);
              tickHandle = window.setInterval(render, TICK_INTERVAL_MS);
            });

            $scope.$on('$destroy', reset);
          }
        ]
      }
    );
  }
];
