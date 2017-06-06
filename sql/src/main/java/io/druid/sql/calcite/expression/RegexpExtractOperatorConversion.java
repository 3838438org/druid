/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.sql.calcite.expression;

import com.google.common.collect.ImmutableList;
import io.druid.query.extraction.RegexDimExtractionFn;
import io.druid.sql.calcite.planner.PlannerContext;
import io.druid.sql.calcite.table.RowSignature;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;

public class RegexpExtractOperatorConversion implements SqlOperatorConversion
{
  private static final String NAME = "REGEXP_EXTRACT";
  private static final SqlFunction SQL_FUNCTION = new RegexpExtractSqlFunction();
  private static final int DEFAULT_INDEX = 0;

  @Override
  public SqlFunction calciteOperator()
  {
    return SQL_FUNCTION;
  }

  @Override
  public DruidExpression toDruidExpression(
      final PlannerContext plannerContext,
      final RowSignature rowSignature,
      final RexNode rexNode
  )
  {
    final RexCall call = (RexCall) rexNode;
    final DruidExpression input = Expressions.toDruidExpression(
        plannerContext,
        rowSignature,
        call.getOperands().get(0)
    );
    if (input == null) {
      return null;
    }

    final RexNode patternRexNode = call.getOperands().get(1);
    final RexNode indexRexNode = call.getOperands().size() >= 3 ? call.getOperands().get(2) : null;

    final DruidExpression patternExpression = Expressions.toDruidExpression(
        plannerContext,
        rowSignature,
        patternRexNode
    );

    final DruidExpression indexExpression = indexRexNode == null
                                            ? DruidExpression.fromExpression(String.valueOf(DEFAULT_INDEX))
                                            : Expressions.toDruidExpression(plannerContext, rowSignature, indexRexNode);

    if (patternExpression == null || indexExpression == null) {
      return null;
    }

    return input.map(
        simpleExtraction -> {
          if (patternRexNode.isA(SqlKind.LITERAL) && (indexRexNode == null || indexRexNode.isA(SqlKind.LITERAL))) {
            final String pattern = RexLiteral.stringValue(patternRexNode);
            final int index = indexRexNode == null ? DEFAULT_INDEX : RexLiteral.intValue(indexRexNode);
            return simpleExtraction.cascade(new RegexDimExtractionFn(pattern, index, true, null));
          } else {
            // Can't handle dynamic pattern or index.
            return null;
          }
        },
        expression -> DruidExpression.functionCall("regexp_extract", input, patternExpression, indexExpression)
    );
  }

  private static class RegexpExtractSqlFunction extends SqlFunction
  {
    RegexpExtractSqlFunction()
    {
      super(
          "REGEXP_EXTRACT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.VARCHAR),
          null,
          OperandTypes.family(
              ImmutableList.of(
                  SqlTypeFamily.CHARACTER,
                  SqlTypeFamily.CHARACTER,
                  SqlTypeFamily.INTEGER
              ),
              i -> i > 1
          ),
          SqlFunctionCategory.STRING
      );
    }
  }
}
