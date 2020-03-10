import uuid from 'uuid/v4';

import { xmlToObject } from '../utils/xml-utils';
import {
  getExternalWsIcon,
  getAtomicWsIcon,
  messageIconName,
  loopIconName,
  compensatoryTargetIconName,
  compensatorySourceIconName,
  timeoutIconName,
  inlineBlockIconName,
  subprocessIconName,
} from '../utils/image-utils';
import { globalResource } from './ResourceModel';

const tagNames = [
  'AtomicWS',
  'ConnectorWS',
  'ExternalWS',
  'MessagePublisherWS',
  'MessageSubscriberWS',
  'InlineBlock',
  'BlockEnd',
  'BlockStart',
  'NestedWS',
  { name: 'Notes', getter: data => (data.Notes && data.Notes.Note) || [] },
];

export default class CXPTModel {
  constructor() {
    this.workStepShapeMap = new Map();
  }

  load(xmlStr) {
    const data = xmlToObject(xmlStr.trim());

    this.prepareData(data.Process);
  }

  prepareData(data) {
    this.data = data;

    let i = 0;
    while (i < tagNames.length) {
      const tagName = typeof tagNames[i] === 'object' ? tagNames[i].name : tagNames[i];
      const shapes = this.getAsArray(tagName);
      shapes.forEach(el => {
        this.workStepShapeMap.set(el.name, el);
        el.id = this.toShapeID(el.id);
      });
      i++;
    }

    const pools =
      this.data.Package && this.data.Package.Pools
        ? this.toArray(this.data.Package.Pools.Pool)
        : [];
    pools.forEach(pool => {
      pool.id = this.toShapeID(pool.Id, 'pool');
      const lanes = pool.Lanes.Lane;
      if (!lanes) {
        return;
      }

      this.getAsArray(lanes).forEach(lane => {
        lane.id = `lane_${lane.Id}`;
      });
    });
  }

  getCommonShapeData(el) {
    const rollbackPoint = el.RollbackPoint === 'TRUE';
    const loopCondition = el.Loop && el.Loop.Condition && !!Object.keys(el.Loop.Condition).length;

    return {
      rollbackPoint,
      id: el.id,
      orgName: el.name,
      loopIcon: loopCondition && this.getIconLocation(loopIconName),
      rollbackIcon: rollbackPoint && this.getIconLocation(compensatoryTargetIconName),
      x: el.x,
      y: el.y,
      height: el.height,
      width: el.width,
      workstepName: el.name,
    };
  }

  getShapes() {
    const atomicShapes = this.getAsArray('AtomicWS');
    return atomicShapes.map(el => {
      let label = (el.AtomicType !== 'START' && el.AtomicType !== 'END' && el.name) || null;
      if (label) {
        label = globalResource.getWorkstepLabel(label);
      }
      const result = {
        ...this.getCommonShapeData(el),
        label,
        type: el.AtomicType,
        atomicType: el.AtomicType,
        performBy: el.PerformBy,
        loop: el.Loop,
        monitorStep: el.MonitorStep,
        messageHandleZZr: el.MessageHandler,
      };
      const icon = getAtomicWsIcon(globalResource, result);
      if (icon) {
        result.img = icon;
      }

      return result;
    });
  }

  getInlineBlock() {
    const connectors = this.getAsArray('InlineBlock');
    return connectors.map(el => ({
      img: this.getIconLocation(inlineBlockIconName),
      label: globalResource.getWorkstepLabel(el.name),
      ...this.getCommonShapeData(el),
    }));
  }

  getNestedWS() {
    const connectors = this.getAsArray('NestedWS');
    return connectors.map(el => ({
      img: this.getIconLocation(subprocessIconName),
      label: globalResource.getWorkstepLabel(el.name),
      ...this.getCommonShapeData(el),
    }));
  }

  getConnectorShapes() {
    const connectors = this.getAsArray('ConnectorWS');
    return connectors.map(el => {
      const connectorType = el.ConnectorType['#text'] || el.ConnectorType;
      const connectorTypeExclusive =
        el.ConnectorType.exclusive && el.ConnectorType.exclusive === 'true';
      const label =
        el.ConnectorType === 'DECISIONSPLIT' && el.ConnectorType.exclusive !== 'true'
          ? globalResource.getWorkstepLabel(el.name)
          : null;
      return {
        ...this.getCommonShapeData(el),
        connectorTypeExclusive,
        label,
        connectorType,
      };
    });
  }

  getExternalShapes() {
    const externals = this.getAsArray('ExternalWS');
    return externals.map(el => ({
      ...this.getCommonShapeData(el),
      img: getExternalWsIcon(globalResource, el.type),
      label: globalResource.getWorkstepLabel(el.name),
      type: el.ConnectorType,
    }));
  }

  getMessagePublisherShapes() {
    const externals = this.getAsArray('MessagePublisherWS');
    return externals.map(el => ({
      ...this.getCommonShapeData(el),
      img: this.getIconLocation(messageIconName),
      name: el.name,
    }));
  }

  getNotesShapes() {
    const notes = (this.data.Notes && this.data.Notes.Note) || [];
    return notes.map(el => {
      let txt = el['#text'] || '';
      try {
        txt = globalResource.getAnnotation(el.shape, el.x, el.y, txt);
      } catch (err) {
        console.log('Annotation error');
        console.log(err);
      }
      const shapeData = {
        ...this.getCommonShapeData(el),
        text: txt,
      };
      delete shapeData.fillColor;
      delete shapeData.stroke;
      shapeData.shape = el.shape;
      if (el.shape === 'sticky') {
        shapeData.fillColor = `RGB(${el.color})`;
      }

      if (el.shape === 'annotation' || el.shape === 'sticky') {
        const connectTo = el.connectTo;
        const connectEl = connectTo && this.getShapeByName(connectTo);
        if (connectEl) {
          shapeData.connectTo = connectEl.data.id;
          shapeData.waypoints = [
            { x: connectEl.data.x, y: connectEl.data.y + connectEl.data.height / 2 },
            { x: shapeData.x, y: shapeData.y + shapeData.height / 2 },
          ];
        }
      }
      return shapeData;
    });
  }

  getMessageSubscriberShapes() {
    const externals = this.getAsArray('MessageSubscriberWS');
    return externals.map(el => ({
      ...this.getCommonShapeData(el),
      img: this.getIconLocation(messageIconName),
      name: el.name,
    }));
  }

  getConnections() {
    const links = this.data.Link.filter(val => val);

    return links.map(el => {
      const sourceEl = this.getShapeByName(el.Source['#text']);
      const targetEl = this.getShapeByName(el.Target['#text']);
      const sourceData = sourceEl.data;
      const targetData = targetEl.data;
      const linkDefault = sourceData.default !== 'false';
      const drawSP = sourceData.ConnectorType === 'DECISIONSPLIT' && linkDefault === true;
      let linkType = el.type;

      if (!linkDefault && sourceEl.tagName !== 'ConnectorWS') {
        linkType = 'diamond';
      }

      let label;
      let labelData;
      let iconName;

      if (linkType === 'compensation') {
        iconName = this.getIconLocation(compensatorySourceIconName);
      } else if (linkType === 'timeout') {
        iconName = this.getIconLocation(timeoutIconName);
      }
      if (
        sourceData.ConnectorType === 'DECISIONSPLIT' ||
        linkType === 'compensation' ||
        linkType === 'timeout' ||
        linkType === 'diamond'
      ) {
        label = globalResource.getProcessLinkLabel(el.Name);
        let labelX;
        let labelY;
        const point1x = Number(el.Point[0].x);
        const point2x = Number(el.Point[1].x);

        const point1y = Number(el.Point[0].y);
        const point2y = Number(el.Point[1].y);

        if (point1x === point2x) {
          //if it's a vertical line..
          labelX = point1x + 10;
          if (point1y < point2y) {
            //going downside..
            labelY = point1y + 20;
          } else {
            //going up
            labelY = point1y - 20;
          }
        } else {
          //if its a horizontal line.
          if (point1x < point2x) {
            //going right side
            labelX = point1x + 10;
          } else {
            //going left side..
            labelX = point1x - 90;
          }
          labelY = point1y - 20;
        }

        labelData = { x: labelX, y: labelY, width: 100, height: 50 };
      }
      return {
        drawSP,
        label,
        labelData,
        points: el.Point,
        img: iconName,
        sourceId: sourceData.id,
        targetId: targetData.id,
      };
    });
  }

  getStartBlocks() {
    return this.getAsArray('BlockStart').map(el => ({
      ...this.getCommonShapeData(el),
      label: globalResource.getWorkstepLabel(el.name),
    }));
  }

  getEndBlocks() {
    return this.getAsArray('BlockEnd').map(el => ({
      ...this.getCommonShapeData(el),
      label: globalResource.getWorkstepLabel(el.name),
    }));
  }

  getShapeByName(name) {
    let i = 0;
    let result = null;
    while (i < tagNames.length) {
      const tagName = typeof tagNames[i] === 'object' ? tagNames[i].name : tagNames[i];
      const shapes = this.getAsArray(tagName);
      const el = shapes.find(el => el.name === name);

      if (el) {
        result = { tagName, data: el };
        break;
      }

      i++;
    }
    return result;
  }

  getPhases() {
    if (!this.data.Phases || !this.data.Phases.Phase) {
      return [];
    }

    const pointX = 0;
    const pointY = 0;
    const phaseHeight = 35;
    const phases = this.data.Phases.Phase.filter(val => val);
    return phases.map(phase => {
      const phaseWidthStr = phase.NodeGraphicsInfos.NodeGraphicsInfo.Width;
      const phaseWidth = parseInt(phaseWidthStr.replace(/px/, ''));

      return {
        label: globalResource.getPhaseLabel(phase),
        x: pointX,
        y: pointY,
        width: phaseWidth,
        height: phaseHeight,
      };
    });
  }

  getLanes() {
    if (!this.data.Package || !this.data.Package.Pools) {
      return [];
    }
    const startX = 0;
    let startY = 0;
    const laneWidth = 800;
    const laneHeaderWidth = 30;
    let laneHeight = 0;
    const pools = this.toArray(this.data.Package.Pools.Pool);
    return pools
      .filter(pool => pool.Lanes.Lane && pool.Lanes.Lane.length)
      .map(pool => {
        const lanes = pool.Lanes.Lane;

        return this.toArray(lanes).map(lane => {
          let {
            Height: laneHeightStr,
            FillColor: fillColor,
            BorderColor: borderColor,
          } = lane.NodeGraphicsInfos.NodeGraphicsInfo;

          laneHeight = Number(laneHeightStr.replace(/px/, ''));
          fillColor = `RGB(${fillColor},16)`;
          borderColor = `RGB(${borderColor})`;

          const result = {
            x: startX - laneHeaderWidth,
            y: startY,
            width: laneWidth,
            height: laneHeight,
            laneHeaderWidth,
            fillColor,
            borderColor,
            id: this.toShapeID(lane.id),
            label: globalResource.getLaneLabel(lane.Id, lane.Name),
          };

          startY += laneHeight;
          return result;
        });
      });
  }

  getAsArray(property) {
    let result;
    if (property === 'Notes') {
      result = tagNames.find(el => el.name === property).getter(this.data);
    } else {
      result = this.data[property];
    }
    return this.toArray(result).filter(val => val);
  }

  toArray(value) {
    if (!value) {
      return [];
    }
    return Array.isArray(value) ? value.filter(val => val) : [value];
  }

  toShapeID(id, key = 'shape') {
    let localId = id;

    if (!localId) {
      localId = uuid().replace(/[-]/gm, '');
    }

    return `${key}_${localId}`;
  }

  getIconLocation(iconName) {
    const themeName = globalResource.get('themeName');
    const base = `../css/${themeName}/`;
    return base + iconName;
  }

  countWorksteps() {
    return this.workStepShapeMap.size;
  }
}