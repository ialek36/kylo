(function () {

    var directive = function () {
        return {
            restrict: "EA",
            bindToController: {
                stepIndex: '@'
            },
            scope: {},
            require: ['thinkbigDefineFeedTable', '^thinkbigStepper'],
            controllerAs: 'vm',
            templateUrl: 'js/define-feed/feed-details/define-feed-table.html',
            controller: "DefineFeedTableController",
            link: function ($scope, element, attrs, controllers) {
                var thisController = controllers[0];
                var stepperController = controllers[1];
                thisController.stepperController = stepperController;
                thisController.totalSteps = stepperController.totalSteps;
            }

        };
    }

    var controller = function ($scope, $http, $mdToast, $filter, RestUrlService, FeedService, FileUpload, BroadcastService) {

        this.defineFeedTableForm = {};
        var self = this;
        this.stepNumber = parseInt(this.stepIndex) + 1
        this.stepperController = null;
        this.model = FeedService.createFeedModel;
        this.isValid = false;
        this.sampleFile = null;
        this.sampleFileOverrideDelimiter = false;
        this.sampleFileDelimiter = null;
        this.tableCreateMethods = [{type: 'MANUAL', name: 'Manual'}, {type: 'SAMPLE_FILE', name: 'Sample File'}];
        this.columnDefinitionDataTypes = ['string', 'int', 'bigint', 'tinyint', 'decimal', 'double', 'float', 'date', 'timestamp', 'boolean', 'binary'];

        self.useUnderscoreInsteadOfSpaces = true;

        /**
         * The possibly Partition formulas
         * @type {string[]}
         */
        this.partitionFormulas = ['val', 'year', 'month', 'day', 'hour', 'min', 'sec'];

        this.feedFormat = '';


        BroadcastService.subscribe($scope, 'DATA_TRANSFORM_SCHEMA_LOADED', onDataTransformSchemaLoaded);

        function onDataTransformSchemaLoaded() {
            validate();
        }

        this.uploadBtnDisabled = false;

        /**
         * when adding a new column
         * @returns {*|{name, description, dataType, precisionScale, dataTypeDisplay, primaryKey, nullable, sampleValues, selectedSampleValue, isValid, _id}}
         */
        function newColumnDefinition() {
            return FeedService.newTableFieldDefinition();
        }

        /**
         * when adding a new column this is also called to synchronize the field policies array with the columns
         * @returns {*|{name, partition, profile, standardization, validation}}
         */
        function newColumnPolicy() {
            return FeedService.newTableFieldPolicy();
        }

        /**
         * When adding a new Partition
         * @param index
         * @returns {{position: *, field: string, sourceField: string, formula: string, sourceDataType: string, _id: *}}
         */
        function newPartitionField(index) {
            return {position: index, field: '', sourceField: '', formula: '', sourceDataType: '', _id: _.uniqueId()}
        }

        function replaceSpaces(str) {
            if (str != undefined) {
                return str.replace(/ /g, '_');
            }
            else {
                return '';
            }
        }



        function showProgress() {
            if (self.stepperController) {
                self.stepperController.showProgress = true;
            }
        }

        function hideProgress() {
            if (self.stepperController) {
                self.stepperController.showProgress = false;
            }
        }
        function resetColumns() {
            self.model.table.tableSchema.fields = [];
            self.model.table.fieldPolicies = [];
        }

        /**
         * Ensure the field names are unique.
         * If not add the angular $error to invalidate the form
         */
        function fieldNamesUnique() {
            //map of names that are not unique
            var notUnique = {}

            //map of names to array of columnDefinitions
            var nameMap = {};
            _.each(self.model.table.tableSchema.fields, function (columnDef) {
                if (nameMap[columnDef.name] == undefined) {
                    nameMap[columnDef.name] = [];
                }
                nameMap[columnDef.name].push(columnDef);
                if (nameMap[columnDef.name].length > 1) {
                    notUnique[columnDef.name] = columnDef.name;
                }
            });

            //invalidate the form if needed
            _.each(self.model.table.tableSchema.fields, function (columnDef) {
                if (notUnique[columnDef.name] != undefined) {
                    self.defineFeedTableForm['name_' + columnDef._id].$setValidity('notUnique', false);
                }
                else {
                    self.defineFeedTableForm['name_' + columnDef._id].$setValidity('notUnique', true);
                }

            });
        }

        /**
         * Ensure the Partition Names are unique
         * If Not add a angular error
         */
        function partitionNamesUnique() {


            // Validate the Partition names are unique respective to other partition names
            _.chain(self.model.table.partitions).groupBy(function (partition) {
                return partition.field
            }).each(function (group, name) {
                if (group.length > 1) {
                    _.each(group, function (partition) {
                        //.invalid another partition matches the same name
                        self.defineFeedTableForm['partition_name' + partition._id].$setValidity('notUnique', false);
                    });
                }
                else {
                    _.each(group, function (partition) {
                        //valid this is a unique partition name
                        self.defineFeedTableForm['partition_name' + partition._id].$setValidity('notUnique', true);
                    });
                }
            });

            //Validate the Partition names are unique respective to the other fields

            //an array of column names
            var columnNames = _.map(self.model.table.tableSchema.fields, function (columnDef) {
                return columnDef.name;
            });
            var countPartitionNames = {};
            //add the angular errors
            _.each(self.model.table.partitions, function (partition) {
                if (partition.formula != undefined && partition.formula != 'val' && _.indexOf(columnNames, partition.field) >= 0) {
                    self.defineFeedTableForm['partition_name' + partition._id].$setValidity('notUnique', false);
                }
            });

        }

        /**
         * Adding a new Column to the schema
         * This is called both when the user clicks the "Add Field" button or when the sample file is uploaded
         * If adding from the UI the {@code columnDef} will be null, otherwise it will be the parsed ColumnDef from the sample file
         * @param columnDef
         */
        this.addColumn = function (columnDef, syncFieldPolicies) {

            if (columnDef == null) {
                columnDef = newColumnDefinition();
            }
            if (columnDef.sampleValues != null && columnDef.sampleValues.length > 0) {
                columnDef.selectedSampleValue = columnDef.sampleValues[0];
            }
            else {
                columnDef.selectedSampleValue = null;
            }
            if (self.useUnderscoreInsteadOfSpaces) {
                columnDef.name = replaceSpaces(columnDef.name);
            }
            //add the column to both the source and destination tables as well as the fieldPolicies array
            self.model.table.tableSchema.fields.push(columnDef);
            self.model.table.fieldPolicies.push(newColumnPolicy())
            self.model.table.sourceTableSchema.fields.push(newColumnDefinition());
            validate();
            if (syncFieldPolicies == undefined || syncFieldPolicies == true) {
                FeedService.syncTableFieldPolicyNames();
            }

        }

        /**
         * Remove a column from the schema
         * @param index
         */
        this.removeColumn = function (index) {
            var columnDef = self.model.table.tableSchema.fields[index];
            self.model.table.tableSchema.fields.splice(index, 1);
            self.model.table.sourceTableSchema.fields.splice(index, 1);

            //remove any partitions using this field
            var matchingPartitions = _.filter(self.model.table.partitions, function (partition) {
                return partition.columnDef.name == columnDef.name;
            });
            if (matchingPartitions) {
                _.each(matchingPartitions, function (partition) {
                    var idx = _.indexOf(self.model.table.partitions, partition.sourceField)
                    if (idx >= 0) {
                        self.removePartitionField(idx);
                    }
                });
            }

            //ensure the field names on the columns are unique again as removing a column might fix a "notUnique" error
            fieldNamesUnique();
            partitionNamesUnique();
            FeedService.syncTableFieldPolicyNames();
            validate();
        }

        /**
         * Add a partition to the schema
         * This is called from the UI when the user clicks "Add Partition"
         */
        this.addPartitionField = function () {
            var partitionLength = self.model.table.partitions.length;
            var partition = newPartitionField(partitionLength);
            self.model.table.partitions.push(partition);
        }

        /**
         * Remove the partition from the schecma
         * @param index
         */
        this.removePartitionField = function (index) {
            self.model.table.partitions.splice(index, 1);
            partitionNamesUnique();
        }

        /**
         * When the schema field changes it needs to
         *  - ensure the names are unique
         *  - update the respective partition names if there is a partition on the field with the 'val' formula
         *  - ensure that partition names are unique since the new field name could clash with an existing partition
         * @param columnDef
         */
        this.onFieldNameChange = function (columnDef) {
            if (self.useUnderscoreInsteadOfSpaces) {
                columnDef.name = replaceSpaces(columnDef.name);
            }
            fieldNamesUnique();
            //update the partitions with "val" on this column so the name matches
            _.each(self.model.table.partitions, function (partition) {
                if (partition.formula == 'val' && partition.columnDef == columnDef) {
                    partition.sourceDataType = columnDef.dataType;
                    partition.sourceField = columnDef.name;
                    self.updatePartitionFieldName(partition);
                }
            });
            partitionNamesUnique();
            FeedService.syncTableFieldPolicyNames();
        }

        /**
         * When a partition Source field changes it needs to
         *  - auto select the formula if there is only 1 in the drop down (i.e. fields other than dates/timestamps will only have the 'val' formula
         *  - ensure the partition data mapping to this source field is correct
         *  - attempt to prefill in the name with some default name.  if its a val formula it will default the partition name to the source field name and leave it disabled
         * @param partition
         */
        this.onPartitionSourceFieldChange = function (partition) {
            //set the partition data to match the selected sourceField
            if (partition.columnDef != null) {
                partition.sourceField = partition.columnDef.name
                partition.sourceDataType = partition.columnDef.dataType;
            }
            else {
                //  console.error("NO FIELD FOR partition ",partition)
            }
            //if there is only 1 option in the formula list then auto select it
            var formulas = $filter('filterPartitionFormula')(self.partitionFormulas, partition);
            if (formulas.length == 1) {
                partition.formula = formulas[0];
            }
            self.updatePartitionFieldName(partition);
            partitionNamesUnique();

        }
        /**
         * When a partition formula changes it needs to
         *  - attempt to prefill in the name with some default name.  if its a val formula it will default the partition name to the source field name and leave it disabled
         * @param partition
         */
        this.onPartitionFormulaChange = function (partition) {
            self.updatePartitionFieldName(partition);
            partitionNamesUnique();
        }

        /**
         * when the partition name changes it needs to
         *  - ensure the names are unique
         *  - ensure no dups (cannot have more than 1 partitoin on the same col/formula
         * @param partition
         */
        this.onPartitionNameChange = function (partition) {
            if (self.useUnderscoreInsteadOfSpaces) {
                partition.field = replaceSpaces(partition.field);
            }
            partitionNamesUnique();
        }

        function unqiueNameValidation() {
            fieldNamesUnique();
            partitionNamesUnique();
        }

        /**
         * Helper method to look through the table columns (not partitions) and find the first one that matches the incoming {@code fieldName}
         * @param fieldName
         * @returns {*|{}}
         */
        this.getColumnDefinition = function (fieldName) {

            return _.find(self.model.table.fields, function (field) {
                return field.name == fieldName;
            });
        }

        /**
         * Ensure that for the partitions the sourceField and sourceDataTypes match the respective schema field data
         */
        function ensurePartitionData() {
            var nameMap = {};
            _.each(self.model.table.partitions, function (partition) {
                if (partition.columnDef == undefined) {
                    var columnDef = self.getColumnDefinition(partition.sourceField);
                    if (columnDef != null) {
                        partition.columnDef = columnDef;
                    }
                    else {
                        //ERROR!!
                        //  console.error("unable to find columnDef for partition ",partition)
                    }
                }

                if (partition.columnDef) {
                    partition.sourceDataType = partition.columnDef.dataType;
                    partition.sourceField = partition.columnDef.name;
                }
            });

        }

        /**
         * if the formula == val assign the field to be the same as the source field, otherwise attempt to prefill the name with the source_formula
         * @param partition
         */
        this.updatePartitionFieldName = function (partition) {
            if (partition.formula != 'val') {
                if (partition.sourceField != null && (partition.field == null || partition.field == '' || partition.field == partition.sourceField + "_")) {
                    partition.field = partition.sourceField + "_" + partition.formula;
                }
            }
            else {
                partition.field = partition.columnDef ? partition.columnDef.name : partition.sourceField;
            }
        }

        function validate(validForm) {
            if (validForm == undefined) {
                validForm = self.defineFeedTableForm.$valid;
            }
            var valid = self.model.templateId != null && self.model.table.method != null && self.model.table.tableSchema.name != null && self.model.table.tableSchema.name != ''
                        && self.model.table.tableSchema.fields.length > 0;
            if (valid) {
                ensurePartitionData();
            }
            self.isValid = valid && validForm;

        };


        //Set the Table Name to be the System Feed Name
        var systemFeedNameWatch = $scope.$watch(function () {
            return self.model.systemFeedName;
        }, function (newVal) {
            self.model.table.tableSchema.name = newVal;
        });

        /**
         * Ensure the form is valid
         * @type {*|function()}
         */
        var formValidWatch = $scope.$watch(function () {
            return self.defineFeedTableForm.$valid;
        }, function (newVal) {
            if (newVal == true) {
                validate(newVal);
            }
            else {
                self.isValid = false;
            }

        });



        var sampleFileWatch = $scope.$watch(function () {
            return self.sampleFile;
        }, function (newVal) {
            if (newVal == null) {
                angular.element('#upload-sample-file-btn').removeClass('md-primary');
            }
            else {
                angular.element('#upload-sample-file-btn').addClass('md-primary');
            }
        });


        this.uploadSampleFile = function () {
            self.uploadBtnDisabled = true;
            showProgress();
            var file = self.sampleFile;
            var params = {};
            var uploadUrl = RestUrlService.UPLOAD_SAMPLE_TABLE_FILE;
            var successFn = function (response) {
                resetColumns();
                angular.forEach(response.fields, function (field) {
                    var col = newColumnDefinition();
                    col = angular.extend(col, field)
                    self.addColumn(col, false);
                });
                FeedService.syncTableFieldPolicyNames();
                //set the feedFormat property
                self.model.table.feedFormat = response.hiveRecordFormat;

                hideProgress();
                self.uploadBtnDisabled = false;
                validate();
                angular.element('#upload-sample-file-btn').removeClass('md-primary');
            }
            var errorFn = function (data) {
                hideProgress();
                self.uploadBtnDisabled = false;
                angular.element('#upload-sample-file-btn').removeClass('md-primary');
            }
            if (this.sampleFileOverrideDelimiter) {
                params['delimiter'] = this.sampleFileDelimiter;
            }
            //clear partitions
            while (self.model.table.partitions.length) {
                self.model.table.partitions.pop();
            }
            FileUpload.uploadFileToUrl(file, uploadUrl, successFn, errorFn, params);
        };

        $scope.$on('$destroy', function () {
            systemFeedNameWatch();
            //tableMethodWatch();
            //tableFieldsWatch();
            // partitionFieldsWatch();
        })


    };

    angular.module(MODULE_FEED_MGR).controller('DefineFeedTableController', controller);

    angular.module(MODULE_FEED_MGR)
        .directive('thinkbigDefineFeedTable', directive);

    angular.module(MODULE_FEED_MGR).filter('filterPartitionFormula', ['FeedService', function (FeedService) {
        return function (formulas, partition) {

            if (partition && partition.sourceField) {
                var columnDef = FeedService.getColumnDefinitionByName(partition.sourceField);
                if (columnDef) {
                    return _.filter(formulas, function (formula) {
                        if (columnDef.dataType != 'date' && columnDef.dataType != 'timestamp' && formula != 'val') {
                            return false;
                        }
                        else {
                            return true;
                        }
                    })

                }
                else {
                    return formulas;
                }
            }
            else {
                return formulas;
            }
        };
    }]);

})();
