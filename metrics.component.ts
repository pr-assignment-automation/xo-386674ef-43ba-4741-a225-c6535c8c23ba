import { ChangeDetectionStrategy, Component, OnDestroy, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { Dispatch } from '@ngxs-labs/dispatch-decorator';
import { Select } from '@ngxs/store';
import {
  ColDef,
  GridOptions,
  IServerSideDatasource,
  IServerSideGetRowsParams,
  ValueFormatterParams,
  ValueSetterParams,
} from 'ag-grid-community';
import { combineLatest, Observable, of, Subject } from 'rxjs';
import { filter, map, takeUntil } from 'rxjs/operators';

import {} from '@app/constants';
import { CostObject, Metric, PageRequest, Scenario, Session, User } from '@app/models';
import { MatSelectComponent } from '@app/shared';
import {
  ChangeTrackerSelector,
  CostObjectState,
  DeleteMetrics,
  LoadCostObjectsWithHierarchies,
  LoadSessions,
  MetricState,
  ResetChanges,
  SaveMetrics,
  ScenarioState,
  SessionState,
  UpdateEntity,
  UserPermissionState,
  UserPreferencesSelector,
  UserSelector,
} from '@app/store';
import { AddMetric, LoadMetrics } from '@app/store';
import { dataTypeKeyValueList, dataTypeNameMap } from '@app/util';

@Component({
  selector: 'ac-metrics',
  templateUrl: './metrics.component.html',
  styleUrls: ['./metrics.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricsComponent implements OnInit, OnDestroy {
  private static headerName: string = 'Name';
  private static fieldName: string = 'Name';
  private static headerDataType: string = 'Data Type';
  private static fieldDataType: string = 'DataType';
  private static headerDataSize: string = 'Data Size';
  private static fieldDataSize: string = 'Size';
  private static headerDescription: string = 'Description';
  private static fieldDescription: string = 'Description';
  private static headerColumnName: string = 'Column Name';
  private static fieldColumnName: string = 'ColumnName';
  private static headerBusinessDimension: string = 'Business Dimension';
  private static fieldBusinessDimension: string = 'CostObjectId';
  private static fieldKey: string = 'key';
  private static fieldValue: string = 'value';
  private static costObjectIdField: string = 'CostObjectId';
  private static selectEditorName: string = 'selectEditor';
  private static whereClauseGlobalMetrics: string = '(it.CostObject.IsGlobals==True)';
  private static whereClauseNonGlobalMetrics: string = '(it.CostObject.IsGlobals==False)';
  private static globalMetricsIdentifier: string = 'global';

  private destroy$: Subject<void> = new Subject<void>();
  private gridOptions: GridOptions;
  private costObjectList: CostObject[];
  private activeTimePeriodId: number;

  readonly fileUploadContext = 'Metrics';

  isUploadEntitiesPaneDroppedDown: boolean = false;
  cannotModify$: Observable<boolean>;
  pageTitle: string = 'Metrics';
  isGlobalMetrics: boolean = false;
  isReadOnlyGrid: boolean = false;
  defaultColDef: ColDef = {
    editable: true,
    sortable: true,
    resizable: true,
    valueSetter: (params: ValueSetterParams): boolean => {
      return this.setMetricFields(params);
    },
  };
  frameworkComponents = {
    selectEditor: MatSelectComponent,
  };
  columnDefs: ColDef[];

  areChangeButtonsDisabled$: Observable<boolean>;

  @Select(ChangeTrackerSelector.hasNoChanges)
  hasNoChanges$: Observable<boolean>;

  @Select(UserPreferencesSelector.getPageSize)
  pageSize$: Observable<number>;

  @Select(MetricState.getTotalCount)
  dataRowCount$: Observable<number>;

  @Select(MetricState.getMetrics)
  dataRowList$: Observable<Metric[]>;

  @Select(ScenarioState.getActiveScenario)
  activeScenario$: Observable<Scenario>;

  @Select(CostObjectState.getCostObjects)
  costObjectList$: Observable<CostObject[]>;

  @Select(UserSelector.getCurrentUser)
  currentUser$: Observable<User>;

  @Select(SessionState.getLastSession)
  lastSession$: Observable<Session>;

  @Select(UserPermissionState.getHasModifyModelPermission)
  hasModifyModelPermission$: Observable<boolean>;

  constructor(private route: ActivatedRoute) {}

  @Dispatch()
  updateMetric(metric: Metric): UpdateEntity {
    return new UpdateEntity({ entity: metric, type: Metric.type });
  }

  @Dispatch()
  deleteMetricsList(): DeleteMetrics {
    return new DeleteMetrics(this.gridOptions.api.getSelectedRows());
  }

  @Dispatch()
  refreshData(): ResetChanges {
    return new ResetChanges();
  }

  @Dispatch()
  save(): SaveMetrics {
    return new SaveMetrics();
  }

  getRowNodeId(metric: Metric): number {
    return metric.MetricId || 0;
  }

  addMetric(): void {
    this.createMetric();
    this.gridOptions.api.refreshCells();
  }

  undo(): void {
    this.gridOptions.api.refreshCells();
    this.gridOptions.api.purgeServerSideCache();
    this.refreshData();
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  ngOnInit(): void {
    this.fetchUserPermissionAndReadOnlyStatus();
    this.getMetricsTypeFromRouteParameters();
    this.loadCostObjects();
    this.retrieveCostObjectsFromObservable();
    this.setChangeButtonsDisabledState();
    this.getScenarioDataFromActiveScenario();
  }

  onGridReady(gridOptions: GridOptions): void {
    this.gridOptions = gridOptions;

    const dataSource: IServerSideDatasource = this.createDataSource();
    this.gridOptions.api.setServerSideDatasource(dataSource);
    this.getReadOnlyStatusFromActiveScenario();
    this.setupGridColumns();
  }

  toggleUploadPaneDroppedDown(): void {
    this.isUploadEntitiesPaneDroppedDown = !this.isUploadEntitiesPaneDroppedDown;
  }

  @Dispatch()
  private loadCostObjects(): LoadCostObjectsWithHierarchies {
    return new LoadCostObjectsWithHierarchies();
  }

  private createDataSource(): IServerSideDatasource {
    const dataRowList$ = this.dataRowList$.pipe(takeUntil(this.destroy$));
    const dataRowCount$ = this.dataRowCount$.pipe(takeUntil(this.destroy$));
    const costObjectData$ = this.costObjectList$.pipe(takeUntil(this.destroy$));
    const loadMetrics = this.loadMetrics;
    const isGlobalMetrics = this.isGlobalMetrics;

    return {
      getRows({ successCallback }: IServerSideGetRowsParams): void {
        const pageRequest: PageRequest = {
          $includeTotalCount: true,
          $where: isGlobalMetrics
            ? MetricsComponent.whereClauseGlobalMetrics
            : MetricsComponent.whereClauseNonGlobalMetrics,
        };

        loadMetrics(pageRequest);

        combineLatest(dataRowList$, dataRowCount$, costObjectData$).subscribe(([dataRowList, dataRowCount]) => {
          successCallback(dataRowList, dataRowCount);
        });
      },
    };
  }

  private getReadOnlyStatusFromActiveScenario(): void {
    this.activeScenario$
      .pipe(
        filter(scenario => !!scenario),
        takeUntil(this.destroy$)
      )
      .subscribe((scenario: Scenario) => {
        this.isReadOnlyGrid = scenario.ReadOnly;
        this.setupGridColumns();
      });
  }

  private fetchUserPermissionAndReadOnlyStatus(): void {
    const scenarioData$ = this.activeScenario$.pipe(
      filter(scenario => !!scenario),
      takeUntil(this.destroy$)
    );
    const userPermissionData$ = this.hasModifyModelPermission$.pipe(takeUntil(this.destroy$));

    this.cannotModify$ = combineLatest(scenarioData$, userPermissionData$).pipe(
      map(([scenario, hasModifyModelPermission]) => scenario.ReadOnly || !hasModifyModelPermission)
    );
  }

  private getMetricsTypeFromRouteParameters(): void {
    this.route.data
      .pipe(
        filter(data => !!data),
        takeUntil(this.destroy$)
      )
      .subscribe(data => {
        this.isGlobalMetrics = data.metricType === MetricsComponent.globalMetricsIdentifier;
        this.pageTitle = data.title;
      });
  }

  private retrieveCostObjectsFromObservable(): void {
    this.costObjectList$
      .pipe(
        filter(costObjects => !!costObjects),
        takeUntil(this.destroy$)
      )
      .subscribe(costObjectList => (this.costObjectList = costObjectList));
  }

  private setChangeButtonsDisabledState(): void {
    this.areChangeButtonsDisabled$ = this.hasNoChanges$.pipe(map(hasNoChanges => hasNoChanges || this.isReadOnlyGrid));
  }

  private getScenarioDataFromActiveScenario(): void {
    this.activeScenario$
      .pipe(
        filter(scenario => !!scenario),
        takeUntil(this.destroy$)
      )
      .subscribe((scenario: Scenario) => {
        this.activeTimePeriodId = scenario.TimePeriodId;
        this.loadSessions(this.activeTimePeriodId);
      });
  }

  @Dispatch()
  private loadMetrics(params: PageRequest): LoadMetrics {
    return new LoadMetrics(params);
  }

  @Dispatch()
  private loadSessions(timePeriodId: number): LoadSessions {
    return new LoadSessions(timePeriodId);
  }

  private setupGridColumns(): void {
    const colDefs = this.createColumnDefinitions();
    this.gridOptions.api.setColumnDefs(colDefs);
    const allColumnIds = colDefs.map(colDef => colDef.field);
    this.gridOptions.columnApi.autoSizeColumns(allColumnIds);
    this.gridOptions.api.sizeColumnsToFit();
  }

  private createColumnDefinitions(): ColDef[] {
    const columns: ColDef[] = [
      {
        checkboxSelection: true,
        resizable: false,
        editable: false,
        width: 32,
      },
      {
        headerName: MetricsComponent.headerName,
        field: MetricsComponent.fieldName,
        editable: !this.isReadOnlyGrid,
      },
      {
        headerName: MetricsComponent.headerDataType,
        field: MetricsComponent.fieldDataType,
        editable: !this.isReadOnlyGrid,
        cellEditor: MetricsComponent.selectEditorName,
        cellEditorParams: {
          items$: of(dataTypeKeyValueList),
          idField: MetricsComponent.fieldKey,
          valueField: MetricsComponent.fieldKey,
          displayValueField: MetricsComponent.fieldValue,
        },
        valueFormatter: (params: ValueFormatterParams) => dataTypeNameMap[params.value],
      },
      {
        headerName: MetricsComponent.headerDataSize,
        field: MetricsComponent.fieldDataSize,
        editable: !this.isReadOnlyGrid,
      },
      {
        headerName: MetricsComponent.headerDescription,
        field: MetricsComponent.fieldDescription,
        editable: !this.isReadOnlyGrid,
      },
      {
        headerName: MetricsComponent.headerColumnName,
        field: MetricsComponent.fieldColumnName,
        editable: !this.isReadOnlyGrid,
      },
    ];

    return !this.isGlobalMetrics ? [...columns, this.getCostObjectTypeColumnDefinition()] : columns;
  }

  private getCostObjectTypeColumnDefinition(): ColDef {
    return {
      headerName: MetricsComponent.headerBusinessDimension,
      field: MetricsComponent.fieldBusinessDimension,
      editable: !this.isReadOnlyGrid,
      cellEditor: MetricsComponent.selectEditorName,
      cellEditorParams: {
        items$: this.costObjectList$,
        idField: MetricsComponent.costObjectIdField,
        valueField: MetricsComponent.costObjectIdField,
        displayValueField: MetricsComponent.fieldName,
      },
      valueFormatter: (params: ValueFormatterParams) => {
        if (!params.data) {
          return params.value;
        }

        const costObject = this.costObjectList
          ? this.costObjectList.find(item => item.CostObjectId === params.data.CostObjectId)
          : null;
        return costObject ? costObject.Name : params.data.CostObjectId;
      },
    };
  }

  @Dispatch()
  private createMetric(): AddMetric {
    return new AddMetric();
  }

  private setMetricFields({ newValue, oldValue, data, colDef }: ValueSetterParams): boolean {
    if (oldValue !== newValue) {
      const metric: Metric = { ...data };
      metric[colDef.field] = newValue;
      this.updateMetric(metric);
    }

    return false;
  }
}